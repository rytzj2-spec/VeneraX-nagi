package io.github.kyosee.venera.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never exported with .nagi configurations or Android backup. */
object CompanionStore {
    private var loaded: JSONObject? = null
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "nagi_companion_v1"
        if (!ks.containsAlias(alias)) KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return ks.getKey(alias, null) as SecretKey
    }
    @Synchronized fun read(c: Context): JSONObject {
        if (loaded == null) {
            val file = AtomicFile(File(c.noBackupFilesDir, "companion.enc"))
            loaded = if (!file.baseFile.exists()) JSONObject() else {
                val bytes = file.readFully()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            }
        }
        return JSONObject(loaded.toString())
    }
    @Synchronized fun update(c: Context, block: (JSONObject) -> Unit) {
        val value = read(c)
        block(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val file = AtomicFile(File(c.noBackupFilesDir, "companion.enc"))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out); loaded = value }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun permissions(c: Context) = read(c).optJSONObject("consent") ?: JSONObject()
    fun enabled(c: Context, category: String) = permissions(c).let { it.optBoolean("sharing") && it.optBoolean(category) }
    fun id() = UUID.randomUUID().toString()
}

/** Bounded offline queue in app-private, excluded-from-backup storage. */
class CompanionQueue(c: Context) : SQLiteOpenHelper(c, File(c.noBackupFilesDir, "companion_queue.db").absolutePath, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue(id TEXT PRIMARY KEY, kind TEXT, at INTEGER, data TEXT)")
        db.execSQL("CREATE TABLE inbox(id INTEGER PRIMARY KEY, data TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun add(kind: String, data: JSONObject) {
        val now = System.currentTimeMillis() / 1000
        val id = data.optString("id").ifEmpty { CompanionStore.id() }
        data.put("id", id)
        writableDatabase.execSQL("INSERT OR IGNORE INTO queue VALUES(?,?,?,?)", arrayOf(id, kind, now, data.toString()))
        writableDatabase.execSQL("DELETE FROM queue WHERE at<?", arrayOf(now - 6 * 86400))
        writableDatabase.execSQL("DELETE FROM queue WHERE id IN (SELECT id FROM queue ORDER BY at DESC LIMIT -1 OFFSET 3000)")
    }
    @Synchronized fun pending(kind: String): JSONArray {
        val array = JSONArray()
        readableDatabase.rawQuery("SELECT data FROM queue WHERE kind=? ORDER BY at LIMIT 50", arrayOf(kind)).use {
            while (it.moveToNext()) array.put(JSONObject(it.getString(0)))
        }
        return array
    }
    @Synchronized fun remove(id: String) { writableDatabase.delete("queue", "id=?", arrayOf(id)) }
    @Synchronized fun clear() { writableDatabase.execSQL("DELETE FROM queue"); writableDatabase.execSQL("DELETE FROM inbox") }
    @Synchronized fun receive(event: JSONObject) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO inbox VALUES(?,?)", arrayOf<Any>(event.getLong("id"), event.toString()))
        writableDatabase.execSQL("DELETE FROM inbox WHERE id IN (SELECT id FROM inbox ORDER BY id DESC LIMIT -1 OFFSET 1500)")
    }
    @Synchronized fun events(): JSONArray {
        val array = JSONArray()
        readableDatabase.rawQuery("SELECT data FROM inbox ORDER BY id DESC LIMIT 200", null).use {
            while (it.moveToNext()) array.put(JSONObject(it.getString(0)))
        }
        return array
    }
}
