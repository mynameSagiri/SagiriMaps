package com.example.geolink;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.geolink.model.UserLocation;
import com.example.geolink.util.DistanceUtils;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.MapEventsOverlay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MapActivity extends AppCompatActivity {
    private static final String TAG = "GeoKovalSync";
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int PANEL_NONE = 0;
    private static final int PANEL_SETTINGS = 1;
    private static final int PANEL_PARTICIPANTS = 2;
    private static final String CHANNEL_ID = "nearby_alerts";
    private static final String[] MQTT_BROKERS = {
            "wss://broker.emqx.io:8084/mqtt",
            "ws://broker.hivemq.com:8000/mqtt",
            "wss://broker.hivemq.com:8884/mqtt",
            "ssl://broker.emqx.io:8883",
            "tcp://broker.emqx.io:1883",
            "tcp://broker.hivemq.com:1883",
            "tcp://test.mosquitto.org:1883",
            "ssl://test.mosquitto.org:8883",
            "tcp://mqtt.eclipseprojects.io:1883"
    };
    private static final long STALE_USER_TIMEOUT_MS = 120000;
    private static final long LOCATION_HEARTBEAT_MS = 10000;
    private static final int[] OTHER_MARKER_COLOR_RES = {
            R.color.gl_marker_1,
            R.color.gl_marker_2,
            R.color.gl_marker_3,
            R.color.gl_marker_4,
            R.color.gl_marker_5,
            R.color.gl_marker_6
    };
    private static final GeoPoint SARATOV_POINT = new GeoPoint(51.5336, 46.0343);
    public static final XYTileSource OSM_FRANCE = new XYTileSource(
            "OSM France",
            0, 19, 256, ".png",
            new String[]{
                    "https://a.tile.openstreetmap.fr/osmfr/",
                    "https://b.tile.openstreetmap.fr/osmfr/",
                    "https://c.tile.openstreetmap.fr/osmfr/"
            },
            "© OpenStreetMap France"
    );

    private MapView mapView;
    private MaterialSwitch shareSwitch;
    private MaterialSwitch switchLiveLocation;
    private MaterialButton btnTabSettings;
    private MaterialButton btnTabParticipants;
    private TextView txtStatus;
    private TextView txtRadius;
    private TextView txtMyLocation;
    private TextView txtNearestUser;
    private LinearLayout participantsContainer;
    private View panelSettings;
    private View panelParticipants;
    private MaterialButton btnSetManualLocation;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private GeoPoint myPoint;

    private MqttAsyncClient mqttClient;
    private String mqttSubscribeTopic;
    private String mqttPublishTopic;
    private boolean mqttConnected;

    private String myUid;
    private String mqttClientSeed;
    private String nickname;
    private String roomSecret;
    private boolean sharingEnabled = true;
    private boolean liveLocationEnabled = true;
    private boolean hasCenteredOnLiveLocation = false;
    private int activePanel = PANEL_NONE;
    private float nearbyRadiusMeters = 250f;

    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, Integer> markerColorByUid = new HashMap<>();
    private final Map<String, Long> lastSeenByUser = new HashMap<>();
    private final Map<String, UserLocation> latestUsers = new HashMap<>();
    private final Set<String> nearbyNotifiedUsers = new HashSet<>();
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (sharingEnabled && myPoint != null) {
                publishMyLocation(myPoint.getLatitude(), myPoint.getLongitude(), true);
            }
            heartbeatHandler.postDelayed(this, LOCATION_HEARTBEAT_MS);
        }
    };

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue("GeoKovalMapApp/1.0 (Android; contact: dev@geokoval.app)");
        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));

        try {
            deleteDatabase("osmdroid.db");
            java.io.File cacheDir = new java.io.File(getApplicationContext().getCacheDir(), "osmdroid");
            if (cacheDir.exists() && cacheDir.isDirectory()) {
                java.io.File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        file.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        setContentView(R.layout.activity_map);

        SharedPreferences prefs = getSharedPreferences("geolink_prefs", MODE_PRIVATE);
        myUid = prefs.getString("local_uid", null);
        if (myUid == null || myUid.isEmpty()) {
            myUid = "user-" + UUID.randomUUID();
            prefs.edit().putString("local_uid", myUid).apply();
        }
        mqttClientSeed = "gk" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        nickname = getIntent().getStringExtra("nickname");
        String roomCode = getIntent().getStringExtra("room");
        roomSecret = getIntent().getStringExtra("room_secret");

        if (nickname == null || roomCode == null || roomSecret == null || roomSecret.isEmpty()) {
            Toast.makeText(this, R.string.missing_profile_data, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mapView = findViewById(R.id.map);
        mapView.setTileSource(OSM_FRANCE);
        mapView.setMultiTouchControls(true);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(SARATOV_POINT);

        TextView txtRoom = findViewById(R.id.txtRoom);
        txtRoom.setText(getString(R.string.room_label, roomCode));

        txtStatus = findViewById(R.id.txtStatus);
        txtRadius = findViewById(R.id.txtRadius);
        txtMyLocation = findViewById(R.id.txtMyLocation);
        txtNearestUser = findViewById(R.id.txtNearestUser);
        btnTabSettings = findViewById(R.id.btnTabSettings);
        btnTabParticipants = findViewById(R.id.btnTabParticipants);
        participantsContainer = findViewById(R.id.participantsContainer);
        panelSettings = findViewById(R.id.panelSettings);
        panelParticipants = findViewById(R.id.panelParticipants);
        shareSwitch = findViewById(R.id.switchShare);
        switchLiveLocation = findViewById(R.id.switchLiveLocation);
        btnSetManualLocation = findViewById(R.id.btnSetManualLocation);
        Slider sliderRadius = findViewById(R.id.sliderRadius);
        MaterialButton btnCenter = findViewById(R.id.btnCenter);
        MaterialButton btnLeaveRoom = findViewById(R.id.btnLeaveRoom);

        updateShareStatus();
        txtRadius.setText(getString(R.string.radius_label, (int) nearbyRadiusMeters));
        txtMyLocation.setText(R.string.my_location_unknown);
        txtNearestUser.setText(R.string.nearest_user_none);
        panelSettings.setVisibility(View.GONE);
        panelParticipants.setVisibility(View.GONE);

        btnTabSettings.setOnClickListener(v -> togglePanel(PANEL_SETTINGS));
        btnTabParticipants.setOnClickListener(v -> togglePanel(PANEL_PARTICIPANTS));

        shareSwitch.setChecked(true);
        shareSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharingEnabled = isChecked;
            updateShareStatus();
            if (!sharingEnabled) {
                publishMyLocation(0, 0, false);
            }
        });

        switchLiveLocation.setChecked(true);
        switchLiveLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            liveLocationEnabled = isChecked;
            btnSetManualLocation.setVisibility(isChecked ? View.GONE : View.VISIBLE);
            hasCenteredOnLiveLocation = false;

            if (isChecked) {
                if (hasLocationPermission()) {
                    startLocationFlow();
                } else {
                    ActivityCompat.requestPermissions(
                            this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                            LOCATION_PERMISSION_REQUEST
                    );
                }
            } else {
                stopLocationFlow();
                Toast.makeText(this, R.string.manual_location_hint, Toast.LENGTH_SHORT).show();
            }
        });

        btnSetManualLocation.setOnClickListener(v -> setManualLocation((GeoPoint) mapView.getMapCenter(), true));

        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                if (!liveLocationEnabled) {
                    runOnUiThread(() -> setManualLocation(p, false));
                    return true;
                }
                return false;
            }
        });
        mapView.getOverlays().add(mapEventsOverlay);

        sliderRadius.addOnChangeListener((slider, value, fromUser) -> {
            nearbyRadiusMeters = value;
            txtRadius.setText(getString(R.string.radius_label, (int) nearbyRadiusMeters));
            nearbyNotifiedUsers.clear();
        });

        btnCenter.setOnClickListener(v -> {
            centerOnMyLocation();
        });

        btnLeaveRoom.setOnClickListener(v -> leaveRoom());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        float savedLat = prefs.getFloat("last_lat", 0f);
        float savedLon = prefs.getFloat("last_lon", 0f);
        if (savedLat != 0f && savedLon != 0f) {
            myPoint = new GeoPoint(savedLat, savedLon);
        } else {
            myPoint = SARATOV_POINT;
        }

        upsertMarker(new UserLocation(myUid, nickname, myPoint.getLatitude(), myPoint.getLongitude(), true, System.currentTimeMillis()));
        String topicRoot = "geolink/rooms/" + roomSecret;
        mqttSubscribeTopic = topicRoot + "/users/+";
        mqttPublishTopic = topicRoot + "/users/" + myUid;

        ensureNotificationChannel();
        requestNotificationPermissionIfNeeded();
        connectMqtt();
        startLocationFlow();
        startHeartbeat();
    }

    private void connectMqtt() {
        tryConnectMqttBroker(0);
    }

    private void tryConnectMqttBroker(int brokerIndex) {
        if (brokerIndex >= MQTT_BROKERS.length) {
            Log.e(TAG, "MQTT: all brokers failed for topic=" + mqttSubscribeTopic);
            runOnUiThread(() -> Toast.makeText(MapActivity.this, R.string.sync_failed_generic, Toast.LENGTH_LONG).show());
            return;
        }

        final String brokerUri = MQTT_BROKERS[brokerIndex];
        Log.i(TAG, "MQTT: trying broker " + brokerUri);

        try {
            closeMqttClient();

            mqttClient = new MqttAsyncClient(brokerUri, mqttClientSeed + brokerIndex, new MemoryPersistence());
            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    mqttConnected = true;
                    Log.i(TAG, "MQTT: connected " + serverURI + ", reconnect=" + reconnect);
                    subscribeRoomTopic();
                    if (sharingEnabled && myPoint != null) {
                        publishMyLocation(myPoint.getLatitude(), myPoint.getLongitude(), true);
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    mqttConnected = false;
                    Log.w(TAG, "MQTT: connection lost", cause);
                    runOnUiThread(() -> Toast.makeText(MapActivity.this, R.string.sync_lost_connection, Toast.LENGTH_SHORT).show());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleIncomingMessage(message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
        } catch (MqttException e) {
            Log.w(TAG, "MQTT: client create failed for " + brokerUri + ", reasonCode=" + e.getReasonCode(), e);
            tryConnectMqttBroker(brokerIndex + 1);
            return;
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(20);
        options.setKeepAliveInterval(20);

        try {
            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG, "MQTT: connect success via " + brokerUri);
                    runOnUiThread(() -> Toast.makeText(MapActivity.this, R.string.sync_connected, Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.w(TAG, "MQTT: connect failed via " + brokerUri, exception);
                    tryConnectMqttBroker(brokerIndex + 1);
                }
            });
        } catch (MqttException e) {
            Log.w(TAG, "MQTT: connect throw via " + brokerUri + ", reasonCode=" + e.getReasonCode(), e);
            tryConnectMqttBroker(brokerIndex + 1);
        }
    }

    private void subscribeRoomTopic() {
        try {
            mqttClient.subscribe(mqttSubscribeTopic, 1);
        } catch (MqttException e) {
            runOnUiThread(() -> Toast.makeText(MapActivity.this, R.string.sync_failed_generic, Toast.LENGTH_SHORT).show());
        }
    }

    private void handleIncomingMessage(MqttMessage message) {
        try {
            JSONObject json = new JSONObject(new String(message.getPayload()));
            String uid = json.optString("uid", "");
            if (uid.isEmpty()) {
                return;
            }

            boolean isSharing = json.optBoolean("sharingEnabled", true);
            String name = json.optString("name", getString(R.string.marker_contact));
            double lat = json.optDouble("latitude", 0.0);
            double lon = json.optDouble("longitude", 0.0);
            long updatedAt = json.optLong("updatedAt", System.currentTimeMillis());

            runOnUiThread(() -> {
                if (!isSharing) {
                    removeMarker(uid);
                    return;
                }

                UserLocation data = new UserLocation(uid, name, lat, lon, true, updatedAt);
                upsertMarker(data);
                checkNearby(data);
                pruneStaleMarkers();
            });
        } catch (Exception ignored) {
        }
    }

    private void upsertMarker(UserLocation data) {
        latestUsers.put(data.uid, data);

        Marker marker = markers.get(data.uid);
        GeoPoint point = new GeoPoint(data.latitude, data.longitude);

        if (marker == null) {
            marker = new Marker(mapView);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setIcon(buildMarkerIcon(resolveMarkerColor(data.uid)));
            markers.put(data.uid, marker);
            mapView.getOverlays().add(marker);
        }

        marker.setPosition(point);
        marker.setTitle(data.name);
        marker.setSnippet("");

        lastSeenByUser.put(data.uid, System.currentTimeMillis());
        mapView.invalidate();
        refreshDetailsPanel();
    }

    private void removeMarker(String uid) {
        Marker marker = markers.remove(uid);
        if (marker != null) {
            mapView.getOverlays().remove(marker);
        }
        latestUsers.remove(uid);
        markerColorByUid.remove(uid);
        lastSeenByUser.remove(uid);
        nearbyNotifiedUsers.remove(uid);
        mapView.invalidate();
        refreshDetailsPanel();
    }

    private void pruneStaleMarkers() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = lastSeenByUser.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getKey().equals(myUid)) {
                continue;
            }
            if (now - entry.getValue() > STALE_USER_TIMEOUT_MS) {
                String staleUid = entry.getKey();
                Marker marker = markers.remove(staleUid);
                if (marker != null) {
                    mapView.getOverlays().remove(marker);
                }
                latestUsers.remove(staleUid);
                markerColorByUid.remove(staleUid);
                nearbyNotifiedUsers.remove(staleUid);
                iterator.remove();
            }
        }
        mapView.invalidate();
        refreshDetailsPanel();
    }

    private void togglePanel(int panel) {
        if (activePanel == panel) {
            activePanel = PANEL_NONE;
            panelSettings.setVisibility(View.GONE);
            panelParticipants.setVisibility(View.GONE);
            btnTabSettings.setChecked(false);
            btnTabParticipants.setChecked(false);
            return;
        }

        activePanel = panel;
        panelSettings.setVisibility(panel == PANEL_SETTINGS ? View.VISIBLE : View.GONE);
        panelParticipants.setVisibility(panel == PANEL_PARTICIPANTS ? View.VISIBLE : View.GONE);
        btnTabSettings.setChecked(panel == PANEL_SETTINGS);
        btnTabParticipants.setChecked(panel == PANEL_PARTICIPANTS);

        refreshDetailsPanel();
    }

    private void refreshDetailsPanel() {
        if (myPoint == null) {
            txtMyLocation.setText(R.string.my_location_unknown);
        } else {
            txtMyLocation.setText(getString(
                    R.string.my_location_template,
                    myPoint.getLatitude(),
                    myPoint.getLongitude()
            ));
        }

        UserLocation nearest = null;
        float nearestDistance = Float.MAX_VALUE;

        for (UserLocation user : latestUsers.values()) {
            if (user == null || user.uid == null || user.uid.equals(myUid) || !user.sharingEnabled || myPoint == null) {
                continue;
            }

            float distance = DistanceUtils.metersBetween(
                    myPoint.getLatitude(),
                    myPoint.getLongitude(),
                    user.latitude,
                    user.longitude
            );

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = user;
            }
        }

        if (nearest == null) {
            txtNearestUser.setText(R.string.nearest_user_none);
        } else {
            txtNearestUser.setText(getString(
                    R.string.nearest_user_template,
                    nearest.name,
                    formatDistance(nearestDistance)
            ));
        }

        List<UserLocation> participants = new ArrayList<>();
        for (UserLocation user : latestUsers.values()) {
            if (user != null && user.uid != null && !user.uid.equals(myUid) && user.sharingEnabled) {
                participants.add(user);
            }
        }

        if (myPoint != null) {
            participants.sort(Comparator.comparingDouble(user -> DistanceUtils.metersBetween(
                    myPoint.getLatitude(),
                    myPoint.getLongitude(),
                    user.latitude,
                    user.longitude
            )));
        }

        participantsContainer.removeAllViews();
        if (participants.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.participants_empty);
            empty.setTextSize(13f);
            empty.setTextColor(ContextCompat.getColor(this, R.color.gl_onSurface));
            participantsContainer.addView(empty);
            return;
        }

        for (UserLocation participant : participants) {
            String distanceText = getString(R.string.distance_unknown);
            if (myPoint != null) {
                float distance = DistanceUtils.metersBetween(
                        myPoint.getLatitude(),
                        myPoint.getLongitude(),
                        participant.latitude,
                        participant.longitude
                );
                distanceText = formatDistance(distance);
            }

            LinearLayout rowContainer = new LinearLayout(this);
            rowContainer.setOrientation(LinearLayout.HORIZONTAL);
            rowContainer.setGravity(Gravity.CENTER_VERTICAL);
            rowContainer.setPadding(0, 8, 0, 8);

            TextView row = new TextView(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            ));
            row.setText(getString(
                    R.string.participant_row_template,
                    participant.name,
                    distanceText,
                    participant.latitude,
                    participant.longitude
            ));
            row.setTextSize(13f);
            row.setTextColor(ContextCompat.getColor(this, R.color.gl_onSurface));

            MaterialButton findButton = new MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
            );
            findButton.setText(R.string.find_participant);
            findButton.setOnClickListener(v -> focusOnParticipant(participant));

            rowContainer.addView(row);
            rowContainer.addView(findButton);
            participantsContainer.addView(rowContainer);
        }
    }

    private void focusOnParticipant(UserLocation participant) {
        if (participant == null) {
            return;
        }

        GeoPoint target = new GeoPoint(participant.latitude, participant.longitude);
        mapView.getController().animateTo(target);
        if (mapView.getZoomLevelDouble() < 15.0) {
            mapView.getController().setZoom(15.0);
        }

        Marker marker = markers.get(participant.uid);
        if (marker != null) {
            marker.showInfoWindow();
        }

        Toast.makeText(
                this,
                getString(R.string.participant_focus_toast, participant.name, participant.latitude, participant.longitude),
                Toast.LENGTH_SHORT
        ).show();
    }

    private String formatDistance(float distanceMeters) {
        if (distanceMeters >= 1000f) {
            return String.format(Locale.US, "%.2f км", distanceMeters / 1000f);
        }
        return String.format(Locale.US, "%.0f м", distanceMeters);
    }

    private void checkNearby(UserLocation data) {
        if (myPoint == null || data.uid.equals(myUid)) {
            return;
        }

        float distance = DistanceUtils.metersBetween(
                myPoint.getLatitude(), myPoint.getLongitude(),
                data.latitude, data.longitude
        );

        if (distance <= nearbyRadiusMeters && !nearbyNotifiedUsers.contains(data.uid)) {
            nearbyNotifiedUsers.add(data.uid);
            pushNearbyNotification(data.name);
            Toast.makeText(
                    MapActivity.this,
                    getString(R.string.distance_template, data.name, (double) distance),
                    Toast.LENGTH_SHORT
            ).show();
        }

        if (distance > nearbyRadiusMeters) {
            nearbyNotifiedUsers.remove(data.uid);
        }
    }

    private void updateShareStatus() {
        txtStatus.setText(sharingEnabled ? R.string.share_on : R.string.share_off);
        int colorRes = sharingEnabled ? R.color.gl_success : R.color.gl_warning;
        txtStatus.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private void startLocationFlow() {
        if (!liveLocationEnabled) {
            return;
        }

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
            return;
        }

        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null && liveLocationEnabled) {
                    double lat = location.getLatitude();
                    double lon = location.getLongitude();
                    myPoint = new GeoPoint(lat, lon);
                    getSharedPreferences("geolink_prefs", MODE_PRIVATE).edit()
                            .putFloat("last_lat", (float) lat)
                            .putFloat("last_lon", (float) lon)
                            .apply();
                    if (!hasCenteredOnLiveLocation) {
                        mapView.getController().animateTo(myPoint);
                        hasCenteredOnLiveLocation = true;
                    }
                    upsertMarker(new UserLocation(myUid, nickname, lat, lon, sharingEnabled, System.currentTimeMillis()));
                    if (sharingEnabled) {
                        publishMyLocation(lat, lon, true);
                    }
                }
            });
        } catch (SecurityException ignored) {
        }

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!liveLocationEnabled) {
                    return;
                }
                if (locationResult.getLastLocation() == null) {
                    return;
                }

                double lat = locationResult.getLastLocation().getLatitude();
                double lon = locationResult.getLastLocation().getLongitude();
                myPoint = new GeoPoint(lat, lon);

                getSharedPreferences("geolink_prefs", MODE_PRIVATE).edit()
                        .putFloat("last_lat", (float) lat)
                        .putFloat("last_lon", (float) lon)
                        .apply();

                if (!hasCenteredOnLiveLocation) {
                    mapView.getController().animateTo(myPoint);
                    hasCenteredOnLiveLocation = true;
                }

                upsertMarker(new UserLocation(myUid, nickname, lat, lon, sharingEnabled, System.currentTimeMillis()));

                if (sharingEnabled) {
                    publishMyLocation(lat, lon, true);
                }
            }
        };

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2500)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    private void stopLocationFlow() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void setManualLocation(GeoPoint point, boolean animateToPoint) {
        if (point == null) {
            return;
        }

        myPoint = point;
        if (animateToPoint) {
            mapView.getController().animateTo(myPoint);
        }

        upsertMarker(new UserLocation(
                myUid,
                nickname,
                point.getLatitude(),
                point.getLongitude(),
                sharingEnabled,
                System.currentTimeMillis()
        ));

        if (sharingEnabled) {
            publishMyLocation(point.getLatitude(), point.getLongitude(), true);
        }

        Toast.makeText(this, R.string.manual_location_set, Toast.LENGTH_SHORT).show();
    }

    private void centerOnMyLocation() {
        Marker myMarker = markers.get(myUid);
        if (myMarker != null && myMarker.getPosition() != null) {
            mapView.getController().animateTo(myMarker.getPosition());
            return;
        }

        if (myPoint != null) {
            mapView.getController().animateTo(myPoint);
            return;
        }

        if (!liveLocationEnabled) {
            mapView.getController().animateTo(SARATOV_POINT);
            Toast.makeText(this, R.string.my_location_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
            return;
        }

        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location == null) {
                    mapView.getController().animateTo(SARATOV_POINT);
                    Toast.makeText(this, R.string.my_location_unavailable, Toast.LENGTH_SHORT).show();
                    return;
                }

                GeoPoint detectedPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                myPoint = detectedPoint;
                upsertMarker(new UserLocation(
                        myUid,
                        nickname,
                        detectedPoint.getLatitude(),
                        detectedPoint.getLongitude(),
                        sharingEnabled,
                        System.currentTimeMillis()
                ));
                mapView.getController().animateTo(detectedPoint);
            });
        } catch (SecurityException ignored) {
        }
    }

    private int resolveMarkerColor(String uid) {
        if (uid.equals(myUid)) {
            return ContextCompat.getColor(this, R.color.gl_marker_me);
        }

        Integer saved = markerColorByUid.get(uid);
        if (saved != null) {
            return saved;
        }

        int index = Math.abs(uid.hashCode()) % OTHER_MARKER_COLOR_RES.length;
        int color = ContextCompat.getColor(this, OTHER_MARKER_COLOR_RES[index]);
        markerColorByUid.put(uid, color);
        return color;
    }

    private BitmapDrawable buildMarkerIcon(int fillColor) {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(18f * density);
        int stroke = Math.max(2, Math.round(2f * density));

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fillColor);
        shape.setStroke(stroke, ContextCompat.getColor(this, R.color.white));
        shape.setSize(size, size);

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        shape.setBounds(0, 0, size, size);
        shape.draw(canvas);
        return new BitmapDrawable(getResources(), bitmap);
    }

    private void publishMyLocation(double lat, double lon, boolean isSharing) {
        if (mqttClient == null || !mqttConnected || !mqttClient.isConnected()) {
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("uid", myUid);
            payload.put("name", nickname);
            payload.put("latitude", lat);
            payload.put("longitude", lon);
            payload.put("sharingEnabled", isSharing);
            payload.put("updatedAt", System.currentTimeMillis());

            MqttMessage message = new MqttMessage(payload.toString().getBytes());
            message.setQos(1);
            message.setRetained(true);
            mqttClient.publish(mqttPublishTopic, message);
        } catch (Exception ignored) {
        }
    }

    private void startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, LOCATION_HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (hasLocationPermission()) {
                if (liveLocationEnabled) {
                    startLocationFlow();
                }
            } else {
                if (liveLocationEnabled) {
                    liveLocationEnabled = false;
                    switchLiveLocation.setChecked(false);
                    btnSetManualLocation.setVisibility(View.VISIBLE);
                    Toast.makeText(this, R.string.live_location_requires_permission, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nearby_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.nearby_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void pushNearbyNotification(String friendName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(getString(R.string.nearby_title))
                .setContentText(getString(R.string.nearby_text, friendName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(friendName.hashCode(), builder.build());
    }

    private void leaveRoom() {
        try {
            if (sharingEnabled && myPoint != null) {
                publishMyLocation(myPoint.getLatitude(), myPoint.getLongitude(), false);
            }
        } catch (Exception ignored) {
        }

        stopHeartbeat();
        stopLocationFlow();
        closeMqttClient();

        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void closeMqttClient() {
        if (mqttClient == null) {
            return;
        }

        try {
            mqttConnected = false;
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
            mqttClient.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Configuration.getInstance().save(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHeartbeat();
        stopLocationFlow();
        closeMqttClient();
    }
}
