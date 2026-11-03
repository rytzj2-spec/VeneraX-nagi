class PlayerErrorMapper {
  const PlayerErrorMapper._();

  static String? toActionableMessage(
    Object error, {
    required bool isBuffering,
  }) {
    final message = error.toString();
    if (message.contains('Failed to recognize file format') ||
        (isBuffering && message.contains('Failed to open'))) {
      return '加载失败, 请尝试更换其他视频来源';
    }
    return null;
  }

  /// Nagisaland suite：网络抖动类错误。mpv / ffmpeg 会自己重连，播放通常还在继续，
  /// 例如 `tcp: ffurl_read returned 0xffffff92`（-110 ETIMEDOUT）。这类错误只记日志，
  /// 由播放器在一段时间后确认是否真的卡住了再提示。
  static bool isRecoverableNetworkError(Object error) {
    final message = error.toString().toLowerCase();
    const keywords = [
      'ffurl_read',
      'tcp:',
      'tls:',
      'http:',
      'https:',
      'hls:',
      'reconnect',
      'timed out',
      'timeout',
      'etimedout',
      'connection reset',
      'connection refused',
      'broken pipe',
      'end of file',
      'stream ends prematurely',
      'error in the pull function',
      'i/o error',
      'io error',
      'keepalive',
      'failed to open segment',
      'unable to open',
      'cannot reuse http connection',
      'network',
      // 常见的负 errno（十六进制补码）：-110 ETIMEDOUT、-104 ECONNRESET、-5 EIO、-32 EPIPE
      '0xffffff92',
      '0xffffff98',
      '0xfffffffb',
      '0xffffffe0',
    ];
    return keywords.any(message.contains);
  }
}
