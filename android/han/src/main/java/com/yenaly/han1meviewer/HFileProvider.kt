package com.yenaly.han1meviewer

import androidx.core.content.FileProvider

/**
 * Nagisaland suite：H 分区自己的 FileProvider（路径见 res/xml/hanime_file_paths.xml）。
 * 用子类是为了和 app 模块已有的 androidx.core.content.FileProvider 分开注册，manifest 合并时不冲突。
 */
class HFileProvider : FileProvider()
