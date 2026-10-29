package com.lyrnox.qingliao;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns only the call's output-device choice and restores the platform choice on teardown. */
final class CallAudioRouter {
  static final int ROUTE_EARPIECE = -1;
  static final int ROUTE_SPEAKER = -2;
  static final int ROUTE_BLUETOOTH_PERMISSION = -3;

  static final class Route {
    final int id;
    final int type;
    final String label;
    final boolean bluetooth;

    Route(int id, int type, String label, boolean bluetooth) {
      this.id = id;
      this.type = type;
      this.label = label;
      this.bluetooth = bluetooth;
    }
  }

  private final Context appContext;
  private final AudioManager manager;
  private boolean active;
  private boolean previousSpeakerphone;
  private int previousCommunicationDeviceId = Integer.MIN_VALUE;

  CallAudioRouter(Context context) {
    appContext = context.getApplicationContext();
    manager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
  }

  synchronized void begin() {
    if (active || manager == null) return;
    previousSpeakerphone = manager.isSpeakerphoneOn();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        AudioDeviceInfo previous = manager.getCommunicationDevice();
        if (previous != null) previousCommunicationDeviceId = previous.getId();
      } catch (SecurityException ignored) {
        // Audio output selection remains usable without reading protected device state.
      }
    }
    active = true;
  }

  synchronized List<Route> availableRoutes() {
    if (manager == null || !active) return Collections.emptyList();
    ArrayList<Route> routes = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
          if (device == null) continue;
          int type = device.getType();
          String label = labelForType(type);
          if (label == null) label = "其他音频设备";
          routes.add(new Route(device.getId(), type, label, isBluetoothType(type)));
        }
      } catch (SecurityException ignored) {
        // Provide built-in routing even if Bluetooth enumeration is permission-gated.
      }
      if (routes.isEmpty()) addBuiltInFallbacks(routes);
      if (!hasBluetoothPermission()) {
        routes.add(new Route(ROUTE_BLUETOOTH_PERMISSION, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            "蓝牙设备（授权后选择）", true));
      }
    } else {
      routes.add(new Route(ROUTE_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
          "听筒 / 自动耳机", false));
      routes.add(new Route(ROUTE_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "扬声器", false));
    }
    return routes;
  }

  synchronized int selectedRouteId() {
    if (manager == null || !active) return ROUTE_EARPIECE;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        AudioDeviceInfo selected = manager.getCommunicationDevice();
        return selected == null ? ROUTE_EARPIECE : selected.getId();
      } catch (SecurityException ignored) {
        return ROUTE_EARPIECE;
      }
    }
    return manager.isSpeakerphoneOn() ? ROUTE_SPEAKER : ROUTE_EARPIECE;
  }

  synchronized String selectedRouteLabel() {
    int selected = selectedRouteId();
    for (Route route : availableRoutes()) if (route.id == selected) return route.label;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        AudioDeviceInfo device = manager == null ? null : manager.getCommunicationDevice();
        if (device != null) return displayLabelForType(device.getType());
      } catch (SecurityException ignored) {
        // Fall back to a useful label without exposing device details.
      }
      return "系统默认";
    }
    return selected == ROUTE_SPEAKER ? "扬声器" : "听筒 / 自动耳机";
  }

  synchronized boolean select(Route route) {
    if (route == null || manager == null || !active || route.id == ROUTE_BLUETOOTH_PERMISSION) return false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        for (AudioDeviceInfo available : manager.getAvailableCommunicationDevices()) {
          if (available != null && (available.getId() == route.id ||
              (route.id < 0 && available.getType() == route.type)))
            return manager.setCommunicationDevice(available);
        }
      } catch (SecurityException ignored) {
        // Fall through to the built-in speaker fallback below.
      }
      if (route.id == ROUTE_SPEAKER || route.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        manager.setSpeakerphoneOn(true);
        return manager.isSpeakerphoneOn();
      }
      if (route.id == ROUTE_EARPIECE || route.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
        manager.setSpeakerphoneOn(false);
        return !manager.isSpeakerphoneOn();
      }
      return false; // The route disappeared while the chooser was open.
    }
    if (route.id == ROUTE_SPEAKER) {
      manager.setSpeakerphoneOn(true);
      return manager.isSpeakerphoneOn();
    }
    if (route.id == ROUTE_EARPIECE) {
      manager.setSpeakerphoneOn(false);
      return !manager.isSpeakerphoneOn();
    }
    return false;
  }

  synchronized void release() {
    if (!active || manager == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      boolean restored = false;
      if (previousCommunicationDeviceId != Integer.MIN_VALUE) {
        try {
          for (AudioDeviceInfo available : manager.getAvailableCommunicationDevices()) {
            if (available != null && available.getId() == previousCommunicationDeviceId) {
              restored = manager.setCommunicationDevice(available);
              break;
            }
          }
        } catch (SecurityException ignored) {
          // The system default is safer than leaving a stale forced route.
        }
      }
      if (!restored) {
        try { manager.clearCommunicationDevice(); } catch (SecurityException ignored) {}
      }
    } else {
      manager.setSpeakerphoneOn(previousSpeakerphone);
    }
    active = false;
    previousCommunicationDeviceId = Integer.MIN_VALUE;
  }

  private boolean hasBluetoothPermission() {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
  }

  static String displayLabelForType(int type) {
    String label = labelForType(type);
    return label == null ? "其他音频设备" : label;
  }

  private static void addBuiltInFallbacks(List<Route> routes) {
    routes.add(new Route(ROUTE_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "听筒", false));
    routes.add(new Route(ROUTE_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "扬声器", false));
  }

  static boolean isBluetoothType(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER));
  }

  static String labelForType(int type) {
    switch (type) {
      case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "听筒";
      case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "扬声器";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET:
      case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
      case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳机";
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙耳机";
      default:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "蓝牙耳机";
          if (type == AudioDeviceInfo.TYPE_BLE_SPEAKER) return "蓝牙音箱";
        }
        return null;
    }
  }
}
