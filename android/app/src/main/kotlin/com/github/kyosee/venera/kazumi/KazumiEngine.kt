package io.github.kyosee.venera.kazumi

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * Nagisaland suite：番剧分区（Kazumi）的 Flutter 引擎。
 * 用 audio_service 插件约定的缓存 id「audio_service」，这样后台播放服务拿到的也是同一个引擎，
 * 不会另起一个跑默认入口（那是漫画分区 VeneraX）的引擎。
 */
object KazumiEngine {
    const val ID = "audio_service"

    @JvmStatic
    @Synchronized
    fun get(context: Context): FlutterEngine {
        FlutterEngineCache.getInstance().get(ID)?.let { return it }
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(context.applicationContext)
        loader.ensureInitializationComplete(context.applicationContext, null)
        val engine = FlutterEngine(context.applicationContext)
        engine.navigationChannel.setInitialRoute("/")
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "kazumiMain")
        )
        FlutterEngineCache.getInstance().put(ID, engine)
        return engine
    }
}
