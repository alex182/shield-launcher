package dev.alex.minlauncher;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself: allowed as a notification listener, it lets
 * {@link MediaNow} read the media sessions (what's playing, its art, its
 * controls) for the visualizer's media controls. Allowed over adb by
 * {@code build.sh install}; notifications themselves are never read.
 */
public class MediaListener extends NotificationListenerService { }
