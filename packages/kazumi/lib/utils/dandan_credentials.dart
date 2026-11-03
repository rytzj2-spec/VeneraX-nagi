// DanDanPlay API credentials for the client signature flow.
// Release/PR CI injects them via --dart-define=DANDANAPI_APPID / DANDANAPI_KEY.
const Map<String, String> dandanCredentials = {
  'id': String.fromEnvironment('DANDANAPI_APPID'),
  'value': String.fromEnvironment('DANDANAPI_KEY'),
};

/// Nagisaland：编译时有没有传弹弹play 凭据（没有的话弹幕接口一定 403）
bool get hasDandanCredentials =>
    dandanCredentials['id']!.isNotEmpty && dandanCredentials['value']!.isNotEmpty;
