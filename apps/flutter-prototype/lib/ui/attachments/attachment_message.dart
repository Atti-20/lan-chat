import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../../application/attachment_controller.dart';
import '../../core/models.dart';
import '../theme.dart';

class AttachmentMessage extends StatelessWidget {
  const AttachmentMessage({
    super.key,
    required this.message,
    required this.own,
    this.controller,
  });

  final ChatMessage message;
  final bool own;
  final AttachmentController? controller;

  @override
  Widget build(BuildContext context) {
    final current = controller;
    if (current == null) return const Text('附件功能在此平台不可用');
    return AnimatedBuilder(
      animation: current,
      builder: (context, _) {
        final attachment = current.parse(message);
        if (attachment == null) {
          return const Text('附件消息无效或当前设备没有直传副本');
        }
        final bytes = current.previewBytes(message);
        final color = own
            ? palette(context)['on-accent']!
            : palette(context)['ink']!;
        return ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 280),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (attachment.image && bytes != null) ...[
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.memory(
                    bytes,
                    key: ValueKey('attachment-preview-${message.key}'),
                    fit: BoxFit.contain,
                    errorBuilder: (_, _, _) => const Text('图片内容无法显示'),
                  ),
                ),
                const SizedBox(height: 8),
              ],
              Row(
                children: [
                  Icon(
                    attachment.image
                        ? CupertinoIcons.photo
                        : CupertinoIcons.doc,
                    color: color,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          attachment.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: color,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        Text(
                          _fileSize(attachment.size),
                          style: TextStyle(
                            color: color.withValues(alpha: .72),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                children: [
                  if (attachment.image && bytes == null)
                    TextButton.icon(
                      key: ValueKey('preview-attachment-${message.key}'),
                      onPressed: current.busy
                          ? null
                          : () => current.loadPreview(message),
                      icon: const Icon(CupertinoIcons.eye, size: 17),
                      label: const Text('预览'),
                    ),
                  TextButton.icon(
                    key: ValueKey('share-attachment-${message.key}'),
                    onPressed: current.busy
                        ? null
                        : () => current.downloadAndShare(message),
                    icon: const Icon(CupertinoIcons.share, size: 17),
                    label: const Text('保存/分享'),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}

String _fileSize(int bytes) {
  if (bytes >= 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MiB';
  }
  if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KiB';
  return '$bytes B';
}
