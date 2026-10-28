package com.example.securetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Timer;
import java.util.TimerTask;

public class CoreTrackerVpnService extends VpnService {

    private static final String CHANNEL_ID = "SecureTrackerChannel";
    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private Timer gpsTimer;
    private LocationManager locationManager;
    private double lastLat = 0.0;
    private double lastLon = 0.0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        // 1. Foreground értesítés, hogy a rendszer ne lője ki a háttérben
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Biztonsági Felügyelet Aktív")
                .setContentText("Wi-Fi szabad / Mobilnet szűrve + GPS fut")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();

        startForeground(1, notification);

        // 2. Okos VPN tűzfal indítása
        startSmartVpnFirewall();

        // 3. GPS háttérfigyelő indítása
        startGpsTracker();

        return START_STICKY;
    }

    private boolean isConnectedToWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }
            } else {
                android.net.NetworkInfo netInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                return netInfo != null && netInfo.isConnected();
            }
        }
        return false;
    }

    private void startSmartVpnFirewall() {
        if (vpnThread != null) return;

        try {
            Builder builder = new Builder();
            builder.addAddress("10.0.0.2", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.setSession("SecureTrackerSmartFirewall");
            
            vpnInterface = builder.establish();

            vpnThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
                        byte[] buffer = new byte[32767];

                        while (!Thread.currentThread().isInterrupted()) {
                            int length = in.read(buffer);
                            if (length > 0) {
                                // Megvizsgáljuk, hogy éppen Wi-Fi-n vagyunk-e
                                if (isConnectedToWifi()) {
                                    // HA WI-FI VAN: Teljesen szabad a pálya, 
                                    // átengedjük a csomagokat a fizikai hálózatra.
                                    out.write(buffer, 0, length);
                                } else {
                                    // HA MOBILNET VAN: Élesbe lép a szigorú szűrés.
                                    // A többi app csomagját eldobjuk (DROP), 
                                    // kivéve a saját appunk védett socketjeit.
                                }
                            }
                            Thread.sleep(20);
                        }
                    } catch (Exception e) {
                        Log.e("VPN", "VPN szál hiba: " + e.getMessage());
                    }
                }
            }, "SmartVpnThread");
            vpnThread.start();
        } catch (Exception e) {
            Log.e("VPN", "Nem sikerült a VPN indítása: " + e.getMessage());
        }
    }

    private void startGpsTracker() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10000, // 10 másodpercenként frissít memóriában
                    5,     // 5 méter elmozdulás
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            lastLat = location.getLatitude();
                            lastLon = location.getLongitude();
                        }
                        @Override public void onStatusChanged(String p, int s, Bundle b) {}
                        @Override public void onProviderEnabled(String p) {}
                        @Override public void onProviderDisabled(String p) {}
                    });
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        // Időzítő: Pontosan 10 percenként (600 000 ms) kiírja a koordinátát
        gpsTimer = new Timer();
        gpsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (lastLat != 0.0 && lastLon != 0.0) {
                    Log.i("GPS_LOGGER", "Aktuális pozíció (10 perces ütemezés): " + lastLat + ", " + lastLon);
                }
            }
        }, 5000, 600000);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Secure Tracker Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (vpnThread != null) vpnThread.interrupt();
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
        }
        if (gpsTimer != null) gpsTimer.cancel();
    }
}
