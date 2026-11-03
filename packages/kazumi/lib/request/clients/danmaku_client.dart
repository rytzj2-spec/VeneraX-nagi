import 'package:dio/dio.dart';
import 'package:kazumi/request/core/dio_factory.dart';
import 'package:kazumi/request/core/network_error_mapper.dart';
import 'package:kazumi/request/core/network_exception.dart';
import 'package:kazumi/utils/dandan_credentials.dart';
import 'package:kazumi/utils/http_headers.dart';
import 'package:kazumi/utils/crypto.dart';

class DanmakuClient {
  DanmakuClient._();

  static final DanmakuClient instance = DanmakuClient._();

  Future<dynamic> get(
    String url, {
    Map<String, dynamic>? queryParameters,
    Map<String, dynamic> headers = const {},
    CancelToken? cancelToken,
  }) async {
    // Nagisaland：编译时没传弹弹play 凭据，服务器一定 403（Missing Authentication Headers），直接说清楚原因
    if (!hasDandanCredentials) {
      throw const NetworkException(
        type: NetworkExceptionType.badResponse,
        message: '没有弹弹play 凭据：编译时没传 DANDANAPI_APPID / DANDANAPI_KEY'
            '（本地编译放在 dart_defines.txt，用 --dart-define-from-file 传入）',
      );
    }
    final timestamp = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final uri = Uri.parse(url);
    final requestHeaders = <String, dynamic>{
      'user-agent': getRandomUA(),
      'referer': '',
      'X-Auth': 1,
      'X-AppId': dandanCredentials['id'],
      'X-Timestamp': timestamp,
      'X-Signature': generateDandanSignature(uri.path, timestamp),
      ...headers,
    };

    try {
      final response = await DioFactory.apiDio.get(
        url,
        queryParameters: queryParameters,
        options: Options(headers: requestHeaders),
        cancelToken: cancelToken,
      );
      return response.data;
    } on DioException catch (e) {
      // Nagisaland：服务器拒绝时带上状态码和弹弹play 给的原因（X-Error-Message 头）
      final code = e.response?.statusCode;
      if (e.type == DioExceptionType.badResponse && code != null) {
        final reason = e.response?.headers.value('X-Error-Message');
        final hint = switch (code) {
          401 || 403 => '凭据无效或签名不对（检查 DANDANAPI_APPID / DANDANAPI_KEY、手机时间是否准确）',
          429 => '请求太频繁，稍后再试',
          _ => '服务器异常，请稍后重试',
        };
        throw NetworkException(
          type: NetworkExceptionType.badResponse,
          message: '弹弹play 返回 $code：$hint${reason == null || reason.isEmpty ? '' : '（$reason）'}',
          statusCode: code,
          rawError: e,
          stackTrace: e.stackTrace,
        );
      }
      throw await NetworkErrorMapper.mapException(e);
    }
  }
}
