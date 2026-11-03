package com.yenaly.han1meviewer

import androidx.core.content.FileProvider

/** Nagisaland suite：和漫画分区的 FileProvider 区分开（同名 provider 在清单合并时会冲突） */
class HFileProvider : FileProvider()
