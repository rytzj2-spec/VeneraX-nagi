import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';

import '../nagi_theme.dart';
import 'emby_api.dart';

/// 影视的图片缓存单独一个库（番剧在同一个进程里用默认缓存库，两个引擎同时写同一个库会锁住）
final videoImageCache = CacheManager(Config('nagiVideoImages', stalePeriod: const Duration(days: 30), maxNrOfCacheObjects: 3000));

class NetImage extends StatelessWidget {
  final String? url;
  final MediaServer server;
  final BoxFit fit;
  final IconData placeholder;
  const NetImage(this.url, this.server, {super.key, this.fit = BoxFit.cover, this.placeholder = Icons.movie_outlined});

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      color: NagiColors.card,
      alignment: Alignment.center,
      child: Icon(placeholder, color: NagiColors.muted, size: 32),
    );
    if (url == null) return fallback;
    return CachedNetworkImage(
      imageUrl: url!,
      cacheManager: videoImageCache,
      httpHeaders: EmbyApi(server).imageHeaders,
      fit: fit,
      fadeInDuration: const Duration(milliseconds: 150),
      placeholder: (_, __) => Container(color: NagiColors.card),
      errorWidget: (_, __, ___) => fallback,
    );
  }
}

/// 竖海报卡片（电影 / 剧）；showServer：聚合视界里标出来自哪个服务器
class PosterCard extends StatelessWidget {
  final MediaItem item;
  final VoidCallback onTap;
  final double width;
  final bool showServer;
  const PosterCard(this.item, {super.key, required this.onTap, this.width = 112, this.showServer = false});

  @override
  Widget build(BuildContext context) {
    final sub = [
      if (item.year != null) '${item.year}',
      if (showServer) item.server.name,
    ].join(' · ');
    return SizedBox(
      width: width,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AspectRatio(
            aspectRatio: 2 / 3,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Stack(fit: StackFit.expand, children: [
                NetImage(item.posterUrl(), item.server),
                if (item.played)
                  const Positioned(top: 6, right: 6, child: _Badge(Icons.check)),
                if (item.progress > 0 && !item.played)
                  Positioned(left: 0, right: 0, bottom: 0, child: ProgressStripe(item.progress)),
              ]),
            ),
          ),
          const SizedBox(height: 6),
          Text(item.type == 'Episode' ? (item.seriesName ?? item.name) : item.name,
              maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13, color: NagiColors.text)),
          if (sub.isNotEmpty || item.type == 'Episode')
            Text(item.type == 'Episode' ? item.displayTitle : sub,
                maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11.5, color: NagiColors.sub)),
        ]),
      ),
    );
  }
}

/// 横图卡片（继续观看 / 单集）
class ThumbCard extends StatelessWidget {
  final MediaItem item;
  final VoidCallback onTap;
  final double width;
  final bool showServer;
  const ThumbCard(this.item, {super.key, required this.onTap, this.width = 220, this.showServer = false});

  @override
  Widget build(BuildContext context) {
    final title = item.type == 'Episode' ? (item.seriesName ?? item.name) : item.name;
    final sub = [
      if (item.type == 'Episode') item.displayTitle,
      if (showServer) item.server.name,
    ].join(' · ');
    return SizedBox(
      width: width,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AspectRatio(
            aspectRatio: 16 / 9,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Stack(fit: StackFit.expand, children: [
                NetImage(item.thumbUrl(), item.server),
                if (item.progress > 0) Positioned(left: 0, right: 0, bottom: 0, child: ProgressStripe(item.progress)),
              ]),
            ),
          ),
          const SizedBox(height: 6),
          Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13, color: NagiColors.text)),
          if (sub.isNotEmpty)
            Text(sub, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11.5, color: NagiColors.sub)),
        ]),
      ),
    );
  }
}

class ProgressStripe extends StatelessWidget {
  final double value;
  const ProgressStripe(this.value, {super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 3,
      color: Colors.black54,
      alignment: Alignment.centerLeft,
      child: FractionallySizedBox(
        widthFactor: value.clamp(0.0, 1.0),
        child: Container(decoration: const BoxDecoration(gradient: NagiColors.selectedGradient)),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  final IconData icon;
  const _Badge(this.icon);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: const BoxDecoration(shape: BoxShape.circle, gradient: NagiColors.selectedGradient),
      child: Icon(icon, size: 12, color: const Color(0xFF18181B)),
    );
  }
}

/// 一排横向滚动的条目，带标题
class Shelf extends StatelessWidget {
  final String title;
  final List<Widget> children;
  final double height;
  final VoidCallback? onMore;
  const Shelf({super.key, required this.title, required this.children, required this.height, this.onMore});

  @override
  Widget build(BuildContext context) {
    if (children.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 8, 8),
        child: Row(children: [
          Expanded(child: Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: NagiColors.text))),
          if (onMore != null) TextButton(onPressed: onMore, child: const Text('全部')),
        ]),
      ),
      SizedBox(
        height: height,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: children.length,
          separatorBuilder: (_, __) => const SizedBox(width: 10),
          itemBuilder: (_, i) => children[i],
        ),
      ),
    ]);
  }
}

class ErrorView extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;
  const ErrorView(this.message, {super.key, this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.cloud_off_outlined, size: 40, color: NagiColors.muted),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center, style: const TextStyle(color: NagiColors.sub)),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onRetry, child: const Text('重试')),
          ],
        ]),
      ),
    );
  }
}

class EmptyView extends StatelessWidget {
  final String message;
  final IconData icon;
  const EmptyView(this.message, {super.key, this.icon = Icons.video_library_outlined});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 44, color: NagiColors.muted),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center, style: const TextStyle(color: NagiColors.sub, height: 1.6)),
        ]),
      ),
    );
  }
}

/// 打开条目：能播的直接进详情（详情里点播放），剧 / 季进详情选集，文件夹进媒体库列表
typedef OpenItem = void Function(BuildContext context, MediaItem item);
