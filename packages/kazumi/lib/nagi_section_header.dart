import 'package:flutter/material.dart';
import 'nagi_theme.dart';

/// Shared section navigation. The native suite owns the application bottom bar.
class NagiSectionDestination {
  const NagiSectionDestination(this.label, this.icon);
  final String label;
  final IconData icon;
}

class NagiSectionAction {
  const NagiSectionAction(this.label, this.icon, this.onPressed);
  final String label;
  final IconData icon;
  final VoidCallback onPressed;
}

class NagiSectionHeader extends StatelessWidget {
  const NagiSectionHeader({
    super.key,
    required this.title,
    required this.destinations,
    required this.selectedIndex,
    required this.onSelected,
    this.actions = const [],
  });

  final String title;
  final List<NagiSectionDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final List<NagiSectionAction> actions;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: NagiColors.bg,
      child: SafeArea(
        bottom: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text(title,
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  if (actions.isNotEmpty)
                    IconButton(
                      tooltip: actions.first.label,
                      icon: Icon(actions.first.icon),
                      onPressed: actions.first.onPressed,
                    ),
                  if (actions.length > 1)
                    PopupMenuButton<int>(
                      tooltip: '$title功能',
                      icon: const Icon(Icons.more_horiz_rounded),
                      onSelected: (index) => actions[index].onPressed(),
                      itemBuilder: (_) => [
                        for (var i = 1; i < actions.length; i++)
                          PopupMenuItem(value: i, child: Row(children: [
                            Icon(actions[i].icon, size: 20),
                            const SizedBox(width: 12),
                            Text(actions[i].label),
                          ])),
                      ],
                    ),
                ],
              ),
            ),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: Row(children: [
                for (var i = 0; i < destinations.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Semantics(
                      selected: selectedIndex == i,
                      button: true,
                      child: TextButton.icon(
                        onPressed: () => onSelected(i),
                        icon: Icon(destinations[i].icon, size: 18),
                        label: Text(destinations[i].label),
                        style: TextButton.styleFrom(
                          minimumSize: const Size(48, 48),
                          foregroundColor: selectedIndex == i ? NagiColors.pink : NagiColors.sub,
                          backgroundColor: selectedIndex == i ? const Color(0xFF332333) : Colors.transparent,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                        ),
                      ),
                    ),
                  ),
              ]),
            ),
            const Divider(height: 1),
          ],
        ),
      ),
    );
  }
}
