package io.github.kyosee.venera.video

import android.content.Context
import android.os.Bundle
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.github.kyosee.venera.kazumi.KazumiEngine
import io.github.kyosee.venera.suite.SuiteNav

/**
 * Nagisaland「影视」分区（Emby / Jellyfin，Dart 代码在 packages/kazumi/lib/nagi_video/）。
 * 和番剧一样跑在自己的 Flutter 引擎上（dart 入口 nagiVideoMain），在同一个任务里常驻，底部加 Nagisaland 分区栏。
 */
class VideoActivity : FlutterActivity() {
    companion object {
        private const val ENGINE_ID = "nagi_video_engine"

        @Synchronized
        fun engine(context: Context): FlutterEngine {
            val cache = FlutterEngineCache.getInstance()
            cache.get(ENGINE_ID)?.let { return it }
            val engine = io.github.kyosee.venera.suite.SuiteEngines.create(context, "nagiVideoMain", "影视")
            cache.put(ENGINE_ID, engine)
            return engine
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // audio_service 插件挂到界面上时会找番剧的引擎，找不到就自己建一个错的：先放占位（见 KazumiEngine）
        KazumiEngine.reserve(this)
        super.onCreate(savedInstanceState)
    }

    override fun provideFlutterEngine(context: Context): FlutterEngine = engine(context)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        SuiteNav.registerEngine("video", flutterEngine)   // 左滑分区栏打开设置用
    }

    // 引擎常驻（切到别的分区再回来，播放 / 浏览状态都在）
    override fun shouldDestroyEngineWithHost(): Boolean = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        SuiteNav.attach(this, "video")
    }

    override fun finish() {
        if (!SuiteNav.interceptFinish(this)) super.finish()
    }
}
