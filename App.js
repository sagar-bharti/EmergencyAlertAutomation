// ============================================================
//  AI Emergency Alert System — Expo App
//  Single-file App.js  |  Works with Expo Go (SDK 50+)
// ============================================================

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, ScrollView,
  Animated, Alert, Vibration, Linking, Modal,
  StatusBar, Dimensions, SafeAreaView,
} from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createStackNavigator } from '@react-navigation/stack';
import * as Location from 'expo-location';
import { Ionicons } from '@expo/vector-icons';
import { Audio } from 'expo-av';
import * as SMS from 'expo-sms';
import * as Updates from 'expo-updates';
//
import call from 'react-native-phone-call';
import SendDirectSms from 'react-native-send-direct-sms';
import { PermissionsAndroid, Platform } from 'react-native';

const { width } = Dimensions.get('window');
const Tab = createBottomTabNavigator();
const Stack = createStackNavigator();

// ─── Server URL ───────────────────────────────────────────────
const SERVER_URL = 'https://ai-server-6-hrwq.onrender.com';

// ─── Shared state (simple context) ──────────────────────────
const AppContext = React.createContext(null);
function useApp() { return React.useContext(AppContext); }

// ─── Constants ───────────────────────────────────────────────
const CONTACTS = [
  { id: 1, name: 'Mummy',  phone: '+91-8085055261', relation: 'Mother',  icon: 'heart',         color: '#FF6B9D' },
  { id: 2, name: 'Papa',   phone: '+91-7987504890', relation: 'Father',  icon: 'shield',        color: '#5B8CFF' },
  { id: 3, name: 'Bhaiya', phone: '+91-8085055261', relation: 'Brother', icon: 'people',        color: '#4CAF82' },
  { id: 4, name: 'Priya',  phone: '+91-7987504890', relation: 'Friend',  icon: 'person-circle', color: '#FF9F43' },
];

const FAKE_ALERTS = [
  { id: 1, time: '2 min ago',  type: 'Emergency', msg: 'SOS sent to all contacts', color: '#FF4757' },
  { id: 2, time: '1 hour ago', type: 'Location',  msg: 'Location shared with Mom', color: '#FF9F43' },
  { id: 3, time: 'Yesterday',  type: 'Test',      msg: 'System test completed',    color: '#2ED573' },
  { id: 4, time: '2 days ago', type: 'Emergency', msg: 'False alarm — resolved',   color: '#5352ED' },
];

// ─── Gradient Card helper ────────────────────────────────────
function GradCard({ colors, style, children }) {
  return (
    <View style={[styles.gradCardOuter, { backgroundColor: colors[0] }, style]}>
      <View style={[StyleSheet.absoluteFill, { backgroundColor: colors[1], opacity: 0.45, borderRadius: 16 }]} />
      {children}
    </View>
  );
}

// ─── Pulse animation component ───────────────────────────────
function PulseRing({ active, color }) {
  const scale   = useRef(new Animated.Value(1)).current;
  const opacity = useRef(new Animated.Value(0.7)).current;

  useEffect(() => {
    if (active) {
      Animated.loop(
        Animated.parallel([
          Animated.sequence([
            Animated.timing(scale,   { toValue: 1.6, duration: 900, useNativeDriver: true }),
            Animated.timing(scale,   { toValue: 1,   duration: 900, useNativeDriver: true }),
          ]),
          Animated.sequence([
            Animated.timing(opacity, { toValue: 0,   duration: 900, useNativeDriver: true }),
            Animated.timing(opacity, { toValue: 0.7, duration: 900, useNativeDriver: true }),
          ]),
        ])
      ).start();
    } else {
      scale.setValue(1);
      opacity.setValue(0.7);
    }
  }, [active]);

  return (
    <Animated.View
      style={{
        position: 'absolute',
        width: 160, height: 160,
        borderRadius: 80,
        backgroundColor: color,
        opacity,
        transform: [{ scale }],
      }}
    />
  );
}

// ════════════════════════════════════════════════════════════
//  HOME SCREEN
// ════════════════════════════════════════════════════════════
function HomeScreen({ navigation }) {
  const { isListening, setIsListening, triggerEmergency, helpCount, setHelpCount, startVoiceDetection, stopVoiceDetection, sendEmergencySMS } = useApp();
  const buttonScale = useRef(new Animated.Value(1)).current;
  const headerAnim  = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(headerAnim, { toValue: 1, duration: 800, useNativeDriver: true }).start();
  }, []);

  const toggleListening = () => {
    Animated.sequence([
      Animated.timing(buttonScale, { toValue: 0.9, duration: 100, useNativeDriver: true }),
      Animated.timing(buttonScale, { toValue: 1,   duration: 100, useNativeDriver: true }),
    ]).start();
    Vibration.vibrate(50);

    if (!isListening) {
      setIsListening(true);
      startVoiceDetection();
    } else {
      setIsListening(false);
      stopVoiceDetection();
    }
  };

  // ✅ BUG FIX 1: handleHelp async banana + await sahi jagah
  const handleHelp = async () => {
  const next = helpCount + 1;
  setHelpCount(next);

  Vibration.vibrate([0, 100, 50, 100]);

  if (next < 2) {
    Alert.alert(
      "⚠️ Warning",
      `Press HELP ${2 - next} more time(s) to trigger emergency!`
    );
    return;
  }

  setHelpCount(0);

  try {
    await requestEmergencyPermissions();

    let coords = null;

    try {
      const loc = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.High,
      });
      coords = loc.coords;
    } catch (e) {}

    const message = coords
      ? `🚨 EMERGENCY ALERT!\nMujhe madad chahiye!\nLocation:\nhttps://maps.google.com/?q=${coords.latitude},${coords.longitude}`
      : `🚨 EMERGENCY ALERT!\nMujhe madad chahiye!`;

    SendDirectSms.sendDirectSms("7987504890", message);
    SendDirectSms.sendDirectSms("8085055261", message);

    triggerEmergency(navigation);

    setTimeout(() => {
      call({
        number: '7987504890',
        prompt: false,
        skipCanOpen: true,
      }).catch(err => console.log(err));
    }, 2000);

  } catch (err) {
    Alert.alert("Error", err.message);
  }
};

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#0A0E27" />
      <ScrollView contentContainerStyle={styles.homeScroll} showsVerticalScrollIndicator={false}>

        <Animated.View style={{ opacity: headerAnim, transform: [{ translateY: headerAnim.interpolate({ inputRange: [0,1], outputRange: [-30,0] }) }] }}>
          <View style={styles.header}>
            <View>
              <Text style={styles.headerSub}>Welcome back</Text>
              <Text style={styles.headerTitle}>AI Emergency{'\n'}Alert System</Text>
            </View>
            <View style={styles.headerBadge}>
              <Ionicons name="shield-checkmark" size={28} color="#2ED573" />
            </View>
          </View>
        </Animated.View>

        <View style={styles.buttonWrapper}>
          <PulseRing active={isListening} color={isListening ? '#FF4757' : '#5B8CFF'} />
          <Animated.View style={{ transform: [{ scale: buttonScale }] }}>
            <TouchableOpacity
              style={[styles.mainBtn, { backgroundColor: isListening ? '#FF4757' : '#5B8CFF' }]}
              onPress={toggleListening}
              activeOpacity={0.85}
            >
              <Ionicons name={isListening ? 'mic' : 'mic-off'} size={44} color="#fff" />
              <Text style={styles.mainBtnText}>{isListening ? 'Stop\nListening' : 'Start\nListening'}</Text>
            </TouchableOpacity>
          </Animated.View>
        </View>

        <GradCard colors={isListening ? ['#1A1F3A', '#2d1a3a'] : ['#1A1F3A', '#0d2d1a']} style={styles.statusCard}>
          <View style={styles.statusRow}>
            <View style={[styles.statusDot, { backgroundColor: isListening ? '#FF4757' : '#2ED573' }]} />
            <Text style={styles.statusText}>
              {isListening ? '🎙️  Listening for distress signals...' : '⏸  System standby — not listening'}
            </Text>
          </View>
          {isListening && (
            <Text style={styles.statusSub}>AI is monitoring for "Help", "Bachao", "Madad"</Text>
          )}
        </GradCard>

        <GradCard colors={['#2d0a0a', '#1a0000']} style={styles.helpCard}>
          <Text style={styles.helpCardTitle}>Panic Trigger</Text>
          <Text style={styles.helpCardSub}>Press HELP twice to send emergency alert instantly</Text>
          <TouchableOpacity style={styles.helpBtn} onPress={handleHelp} activeOpacity={0.8}>
            <Text style={styles.helpBtnText}>🆘  HELP  ({helpCount}/2)</Text>
          </TouchableOpacity>
        </GradCard>

        <Text style={styles.sectionTitle}>System Modules</Text>
        <View style={styles.featureRow}>
          {[
            { icon: 'mic',         label: 'Voice AI', color: '#5B8CFF', desc: 'Keyword detection' },
            { icon: 'location',    label: 'GPS',      color: '#2ED573', desc: 'Live tracking'      },
            { icon: 'camera',      label: 'Camera',   color: '#FF9F43', desc: 'Auto capture'       },
            { icon: 'chatbubbles', label: 'Alerts',   color: '#FF4757', desc: 'Multi-channel'      },
          ].map(f => (
            <View key={f.label} style={styles.featureCard}>
              <View style={[styles.featureIcon, { backgroundColor: f.color + '22' }]}>
                <Ionicons name={f.icon} size={22} color={f.color} />
              </View>
              <Text style={styles.featureLabel}>{f.label}</Text>
              <Text style={styles.featureDesc}>{f.desc}</Text>
            </View>
          ))}
        </View>

        <Text style={styles.sectionTitle}>Trusted Contacts</Text>
        {CONTACTS.map(c => (
          <View key={c.id} style={styles.contactRow}>
            <View style={[styles.contactAvatar, { backgroundColor: c.color + '33' }]}>
              <Ionicons name={c.icon} size={20} color={c.color} />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.contactName}>{c.name}</Text>
              <Text style={styles.contactSub}>{c.relation} · {c.phone}</Text>
            </View>
            <TouchableOpacity onPress={() => Linking.openURL(`tel:${c.phone}`)}>
              <Ionicons name="call-outline" size={20} color="#5B8CFF" />
            </TouchableOpacity>
          </View>
        ))}

        <View style={{ height: 30 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

// ════════════════════════════════════════════════════════════
//  EMERGENCY SCREEN
// ════════════════════════════════════════════════════════════
function EmergencyScreen({ navigation }) {
  const { location, setLocation } = useApp();
  const [alertSent,  setAlertSent]  = useState(false);
  const [locLoading, setLocLoading] = useState(false);
  const flashAnim = useRef(new Animated.Value(1)).current;
  const slideAnim = useRef(new Animated.Value(50)).current;

  useEffect(() => {
    Animated.loop(
      Animated.sequence([
        Animated.timing(flashAnim, { toValue: 0.3, duration: 500, useNativeDriver: true }),
        Animated.timing(flashAnim, { toValue: 1,   duration: 500, useNativeDriver: true }),
      ])
    ).start();
    Animated.timing(slideAnim, { toValue: 0, duration: 400, useNativeDriver: true }).start();
    fetchLocation();
    setTimeout(() => setAlertSent(true), 1500);
    Vibration.vibrate([0, 300, 200, 300, 200, 300]);
  }, []);

  const fetchLocation = async () => {
    setLocLoading(true);
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      if (status === 'granted') {
        const loc = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High });
        setLocation(loc.coords);
      } else {
        setLocation({ latitude: 26.2183, longitude: 78.1828 });
      }
    } catch {
      setLocation({ latitude: 26.2183, longitude: 78.1828 });
    }
    setLocLoading(false);
  };

  const openMaps = () => {
    if (!location) return;
    Linking.openURL(`https://maps.google.com/?q=${location.latitude},${location.longitude}`);
  };

  return (
    <SafeAreaView style={[styles.safeArea, { backgroundColor: '#0d0000' }]}>
      <StatusBar barStyle="light-content" backgroundColor="#0d0000" />
      <ScrollView contentContainerStyle={{ padding: 20 }}>

        <Animated.View style={[styles.emergencyBar, { opacity: flashAnim }]}>
          <Text style={styles.emergencyBarText}>🚨  EMERGENCY DETECTED  🚨</Text>
        </Animated.View>

        <Animated.View style={{ transform: [{ translateY: slideAnim }] }}>
          <View style={styles.emergencyCard}>
            <Ionicons name="alert-circle" size={48} color="#FF4757" style={{ alignSelf: 'center', marginBottom: 8 }} />
            <Text style={styles.emergencyTitle}>Emergency Activated!</Text>
            <Text style={styles.emergencyMsg}>Your distress signal has been detected. Help is being notified.</Text>
            {alertSent && (
              <View style={styles.alertSentBadge}>
                <Ionicons name="checkmark-circle" size={16} color="#2ED573" />
                <Text style={styles.alertSentText}>  Alerts sent to all 4 trusted contacts</Text>
              </View>
            )}
          </View>

          <View style={styles.emergencyCard}>
            <View style={styles.emergencyCardHeader}>
              <Ionicons name="location" size={20} color="#FF9F43" />
              <Text style={styles.emergencyCardTitle}>  Your Location</Text>
            </View>
            {locLoading ? (
              <Text style={styles.locText}>📡 Fetching GPS coordinates...</Text>
            ) : location ? (
              <>
                <Text style={styles.locText}>Lat: {location.latitude.toFixed(6)}</Text>
                <Text style={styles.locText}>Lng: {location.longitude.toFixed(6)}</Text>
                <TouchableOpacity style={styles.mapBtn} onPress={openMaps}>
                  <Ionicons name="map" size={16} color="#fff" />
                  <Text style={styles.mapBtnText}>  Open in Maps</Text>
                </TouchableOpacity>
              </>
            ) : (
              <Text style={styles.locText}>Location unavailable</Text>
            )}
          </View>

          <View style={styles.emergencyCard}>
            <View style={styles.emergencyCardHeader}>
              <Ionicons name="camera" size={20} color="#5B8CFF" />
              <Text style={styles.emergencyCardTitle}>  Camera Module</Text>
            </View>
            <View style={styles.cameraPreview}>
              <Ionicons name="camera-outline" size={40} color="#ffffff44" />
              <Text style={styles.cameraText}>📸 Auto-capture active{'\n'}Images sent to contacts</Text>
            </View>
          </View>

          <View style={styles.emergencyCard}>
            <View style={styles.emergencyCardHeader}>
              <Ionicons name="people" size={20} color="#FF6B9D" />
              <Text style={styles.emergencyCardTitle}>  Notified Contacts</Text>
            </View>
            {CONTACTS.map(c => (
              <View key={c.id} style={styles.contactRow}>
                <View style={[styles.contactAvatar, { backgroundColor: c.color + '33' }]}>
                  <Ionicons name={c.icon} size={18} color={c.color} />
                </View>
                <View style={{ flex: 1 }}>
                  <Text style={styles.contactName}>{c.name} <Text style={styles.alertSentText}>✓ Notified</Text></Text>
                  <Text style={styles.contactSub}>{c.phone}</Text>
                </View>
              </View>
            ))}
          </View>

          <View style={styles.emergencyActions}>
            <TouchableOpacity style={[styles.actionBtn, { backgroundColor: '#FF4757' }]} onPress={() => Alert.alert('Alert', 'Emergency alert re-sent!')}>
              <Ionicons name="send" size={18} color="#fff" />
              <Text style={styles.actionBtnText}>Send Alert</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.actionBtn, { backgroundColor: '#FF9F43' }]} onPress={openMaps}>
              <Ionicons name="location" size={18} color="#fff" />
              <Text style={styles.actionBtnText}>Share Location</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.actionBtn, { backgroundColor: '#2ED573' }]} onPress={() => Linking.openURL('tel:112')}>
              <Ionicons name="call" size={18} color="#fff" />
              <Text style={styles.actionBtnText}>Call 112</Text>
            </TouchableOpacity>
          </View>

          <TouchableOpacity style={styles.cancelBtn} onPress={() => { Vibration.cancel(); navigation.goBack(); }}>
            <Text style={styles.cancelBtnText}>✕  Cancel Emergency</Text>
          </TouchableOpacity>
        </Animated.View>
      </ScrollView>
    </SafeAreaView>
  );
}

// ════════════════════════════════════════════════════════════
//  ALERTS SCREEN
// ════════════════════════════════════════════════════════════
function AlertsScreen() {
  const slideAnims = FAKE_ALERTS.map(() => useRef(new Animated.Value(40)).current);

  useEffect(() => {
    FAKE_ALERTS.forEach((_, i) => {
      Animated.timing(slideAnims[i], {
        toValue: 0, duration: 400, delay: i * 100, useNativeDriver: true,
      }).start();
    });
  }, []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={{ padding: 20 }}>
        <Text style={styles.screenTitle}>Alert History</Text>
        {FAKE_ALERTS.map((a, i) => (
          <Animated.View key={a.id} style={{ transform: [{ translateY: slideAnims[i] }] }}>
            <View style={styles.alertItem}>
              <View style={[styles.alertDot, { backgroundColor: a.color }]} />
              <View style={{ flex: 1 }}>
                <Text style={styles.alertType}>{a.type}</Text>
                <Text style={styles.alertMsg}>{a.msg}</Text>
                <Text style={styles.alertTime}>{a.time}</Text>
              </View>
              <Ionicons name="chevron-forward" size={16} color="#ffffff44" />
            </View>
          </Animated.View>
        ))}

        <Text style={[styles.sectionTitle, { marginTop: 24 }]}>This Month</Text>
        <View style={styles.statsRow}>
          {[
            { label: 'SOS Sent', value: '3', color: '#FF4757' },
            { label: 'Contacts', value: '4', color: '#5B8CFF' },
            { label: 'Safe',     value: '2', color: '#2ED573' },
          ].map(s => (
            <View key={s.label} style={[styles.statCard, { borderTopColor: s.color }]}>
              <Text style={[styles.statValue, { color: s.color }]}>{s.value}</Text>
              <Text style={styles.statLabel}>{s.label}</Text>
            </View>
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

// ════════════════════════════════════════════════════════════
//  SETTINGS SCREEN
// ════════════════════════════════════════════════════════════
function SettingsScreen() {
  const [settings, setSettings] = useState({
    autoCall: true, smsBackup: true, liveTrack: false, vibration: true,
  });
  const toggle = key => setSettings(p => ({ ...p, [key]: !p[key] }));

  const rows = [
    { key: 'autoCall',  label: 'Auto Call Contacts',   icon: 'call',           desc: 'Call automatically during SOS' },
    { key: 'smsBackup', label: 'SMS Backup Alert',     icon: 'chatbox',        desc: 'Send SMS if internet is off'   },
    { key: 'liveTrack', label: 'Live Location Track',  icon: 'navigate',       desc: 'Share location until safe'     },
    { key: 'vibration', label: 'Vibration Alerts',     icon: 'phone-portrait', desc: 'Vibrate on emergency trigger'  },
  ];

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={{ padding: 20 }}>
        <Text style={styles.screenTitle}>Settings</Text>

        <GradCard colors={['#1A1F3A', '#2d1a3a']} style={{ marginBottom: 20 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <View style={styles.profileAvatar}>
              <Ionicons name="person" size={28} color="#5B8CFF" />
            </View>
            <View>
              <Text style={styles.profileName}>User Profile</Text>
              <Text style={styles.profileSub}>4 trusted contacts · Protection active</Text>
            </View>
          </View>
        </GradCard>

        <Text style={styles.sectionTitle}>Alert Settings</Text>
        {rows.map(r => (
          <View key={r.key} style={styles.settingRow}>
            <View style={[styles.settingIcon, { backgroundColor: '#5B8CFF22' }]}>
              <Ionicons name={r.icon} size={18} color="#5B8CFF" />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.settingLabel}>{r.label}</Text>
              <Text style={styles.settingDesc}>{r.desc}</Text>
            </View>
            <TouchableOpacity
              style={[styles.toggleTrack, { backgroundColor: settings[r.key] ? '#5B8CFF' : '#ffffff22' }]}
              onPress={() => toggle(r.key)}
            >
              <View style={[styles.toggleThumb, { transform: [{ translateX: settings[r.key] ? 18 : 0 }] }]} />
            </TouchableOpacity>
          </View>
        ))}

        <Text style={[styles.sectionTitle, { marginTop: 24 }]}>Emergency Contacts</Text>
        {CONTACTS.map(c => (
          <View key={c.id} style={styles.contactRow}>
            <View style={[styles.contactAvatar, { backgroundColor: c.color + '33' }]}>
              <Ionicons name={c.icon} size={18} color={c.color} />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.contactName}>{c.name} — {c.relation}</Text>
              <Text style={styles.contactSub}>{c.phone}</Text>
            </View>
            <Ionicons name="create-outline" size={18} color="#ffffff44" />
          </View>
        ))}

        <View style={[styles.emergencyCard, { marginTop: 24, alignItems: 'center' }]}>
          <Ionicons name="shield-checkmark" size={32} color="#2ED573" />
          <Text style={[styles.emergencyTitle, { fontSize: 15, marginTop: 6 }]}>AI Emergency Safety System</Text>
          <Text style={[styles.emergencyMsg, { textAlign: 'center' }]}>v1.0.0 — Prototype{'\n'}Built with Expo + React Native</Text>
        </View>

        <View style={{ height: 30 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

// ════════════════════════════════════════════════════════════
//  HOME STACK
// ════════════════════════════════════════════════════════════
function HomeStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="Home"      component={HomeScreen} />
      <Stack.Screen name="Emergency" component={EmergencyScreen} />
    </Stack.Navigator>
  );
}

// ════════════════════════════════════════════════════════════
//  ROOT APP
// ════════════════════════════════════════════════════════════
export default function App() {
  const [isListening,     setIsListening]     = useState(false);
  const [location,        setLocation]        = useState(null);
  const [helpCount,       setHelpCount]       = useState(0);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const recordingRef = useRef(null);
  const listeningRef = useRef(false);

  // ─── OTA Update Check ─────────────────────────────────────
  useEffect(() => {
    checkForUpdates();
  }, []);

  async function checkForUpdates() {
    try {
      const update = await Updates.checkForUpdateAsync();
      if (update.isAvailable) setUpdateAvailable(true);
    } catch (e) {
      console.log('Update check failed:', e);
    }
  }

  async function downloadUpdate() {
    await Updates.fetchUpdateAsync();
    await Updates.reloadAsync();
  }

  // ─── Server Keep Alive ────────────────────────────────────
  useEffect(() => {
    const keepAlive = setInterval(async () => {
      try {
        await fetch(`${SERVER_URL}/health`);
        console.log('✅ Server ping done');
      } catch (e) {
        console.log('❌ Server ping failed');
      }
    }, 4 * 60 * 1000);
    return () => clearInterval(keepAlive);
  }, []);

  // ─── isListening → ref sync ───────────────────────────────
  useEffect(() => {
    listeningRef.current = isListening;
  }, [isListening]);

  // ─── SMS Send ─────────────────────────────────────────────
  async function sendEmergencySMS(coords) {
    try {
      const isAvailable = await SMS.isAvailableAsync();
      if (!isAvailable) return;

      const locationText = coords
        ? `https://maps.google.com/?q=${coords.latitude},${coords.longitude}`
        : 'Location unavailable';

      const phones  = CONTACTS.map(c => c.phone.replace(/[-\s]/g, ''));
      const message = `🚨 EMERGENCY ALERT!\nMujhe madad chahiye!\nLocation: ${locationText}\n- EmergencyApp`;

      await SMS.sendSMSAsync(phones, message);
    } catch (e) {
      console.error('SMS error:', e);
    }
  }

  // ─── Audio Server ─────────────────────────────────────────
  async function sendAudioToServer(audioUri) {
    try {
      console.log('📤 sendAudioToServer started', { audioUri });
      if (!audioUri) return;

      const formData = new FormData();
      formData.append('audio', {
        uri: audioUri,
        type: 'audio/m4a',
        name: 'recording.m4a',
      });

      // ✅ 60 second timeout — Render free tier slow hota hai
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 60000);

      const response = await fetch(`${SERVER_URL}/analyze`, {
        method: 'POST',
        body: formData,
        signal: controller.signal,
        headers: { Accept: 'application/json' },
      });
      clearTimeout(timeout);

      const responseText = await response.text();
      console.log('📄 Server response:', responseText);

      const result = JSON.parse(responseText);
      console.log('🧠 Result:', result);

      if (result.emergency) {
        Vibration.vibrate([0, 300, 200, 300]);
        Alert.alert('🚨 Emergency!', `Suna: "${result.transcript}"\nSMS bhej raha hun!`);
        let coords = null;
        try {
          const loc = await Location.getCurrentPositionAsync({});
          coords = loc.coords;
        } catch (e) {}
        await sendEmergencySMS(coords);
      } else {
        // Debug: server ne kya suna
        Alert.alert('✅ Server ne suna', `"${result.transcript || 'kuch nahi'}"\nEmergency: ${result.emergency}`);
      }

    } catch (err) {
      // ✅ Screen pe error dikhega
      Alert.alert('❌ Error', err.name === 'AbortError'
        ? 'Server timeout — 60 sec se zyada laga'
        : err.message || 'Unknown error');
      console.error('sendAudioToServer error:', err);
    }
  }

  // ─── Voice Detection ──────────────────────────────────────
  async function startVoiceDetection() {
    try {
      console.log('🟢 startVoiceDetection started');

      const { status } = await Audio.requestPermissionsAsync();
      if (status !== 'granted') {
        Alert.alert('Permission chahiye', 'Microphone access do');
        return;
      }

      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
        interruptionModeAndroid: Audio.INTERRUPTION_MODE_ANDROID_DO_NOT_MIX,
        shouldDuckAndroid: true,
        staysActiveInBackground: false,
      });

      if (recordingRef.current) {
        try { await recordingRef.current.stopAndUnloadAsync(); } catch (e) {}
        recordingRef.current = null;
      }

      const { recording } = await Audio.Recording.createAsync(
        Audio.RecordingOptionsPresets.HIGH_QUALITY
      );
      recordingRef.current = recording;
      console.log('🎙️ Recording started');

      setTimeout(async () => {
        if (!recordingRef.current) return;

        try {
          await recordingRef.current.stopAndUnloadAsync();
          const uri = recordingRef.current.getURI();
          console.log('⏹ Recording stopped', { uri });
          recordingRef.current = null;

          if (!uri) return;

          await sendAudioToServer(uri);

          if (listeningRef.current) {
            setTimeout(() => startVoiceDetection(), 500);
          }
        } catch (e) {
          console.error('❌ stop recording error:', e);
          if (listeningRef.current) {
            setTimeout(() => startVoiceDetection(), 2000);
          }
        }
      }, 5000);

    } catch (err) {
      console.error('❌ startVoiceDetection error:', err);
      if (listeningRef.current) {
        setTimeout(() => startVoiceDetection(), 2000);
      }
    }
  }

  async function stopVoiceDetection() {
    listeningRef.current = false;
    if (recordingRef.current) {
      try { await recordingRef.current.stopAndUnloadAsync(); } catch (e) {}
      recordingRef.current = null;
    }
  }

  // ─── Emergency Trigger ────────────────────────────────────
  const triggerEmergency = useCallback((navigation) => {
    setIsListening(false);
    navigation.navigate('Emergency');
  }, []);

  // ✅ BUG FIX 3: App() closing bracket sahi jagah pe
  return (
    <AppContext.Provider value={{
      isListening, setIsListening,
      location, setLocation,
      helpCount, setHelpCount,
      triggerEmergency,
      startVoiceDetection,
      stopVoiceDetection,
      sendEmergencySMS,
    }}>
      <NavigationContainer>
        <Tab.Navigator
          screenOptions={({ route }) => ({
            headerShown: false,
            tabBarStyle: styles.tabBar,
            tabBarActiveTintColor: '#5B8CFF',
            tabBarInactiveTintColor: '#ffffff55',
            tabBarLabelStyle: { fontSize: 11, marginBottom: 4 },
            tabBarIcon: ({ focused, color }) => {
              const icons = {
                HomeTab:  focused ? 'home'          : 'home-outline',
                Alerts:   focused ? 'notifications' : 'notifications-outline',
                Settings: focused ? 'settings'      : 'settings-outline',
              };
              return <Ionicons name={icons[route.name]} size={22} color={color} />;
            },
          })}
        >
          <Tab.Screen name="HomeTab"  component={HomeStack}      options={{ title: 'Home'     }} />
          <Tab.Screen name="Alerts"   component={AlertsScreen}   options={{ title: 'Alerts'   }} />
          <Tab.Screen name="Settings" component={SettingsScreen} options={{ title: 'Settings' }} />
        </Tab.Navigator>
      </NavigationContainer>

      {/* OTA Update Modal */}
      <Modal visible={updateAvailable} transparent animationType="slide">
        <View style={{ flex:1, backgroundColor:'#00000099', justifyContent:'center', alignItems:'center' }}>
          <View style={{ backgroundColor:'#1A1F3A', borderRadius:16, padding:24, width:'85%', alignItems:'center', borderWidth:1, borderColor:'#5B8CFF44' }}>
            <Ionicons name="cloud-download" size={40} color="#5B8CFF" style={{ marginBottom:10 }} />
            <Text style={{ color:'#fff', fontSize:20, fontWeight:'800', marginBottom:8 }}>🎉 Naya Update!</Text>
            <Text style={{ color:'#ffffff88', fontSize:13, textAlign:'center', marginBottom:20 }}>
              App ka naya version aa gaya hai. Update karo abhi!
            </Text>
            <TouchableOpacity
              style={{ backgroundColor:'#5B8CFF', paddingVertical:12, paddingHorizontal:32, borderRadius:12, marginBottom:10 }}
              onPress={downloadUpdate}
            >
              <Text style={{ color:'#fff', fontWeight:'800', fontSize:16 }}>Update Karo</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setUpdateAvailable(false)}>
              <Text style={{ color:'#ffffff55', fontSize:13 }}>Baad mein</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

    </AppContext.Provider>
  );
}

// ════════════════════════════════════════════════════════════
//  STYLES
// ════════════════════════════════════════════════════════════
const styles = StyleSheet.create({
  safeArea:            { flex: 1, backgroundColor: '#0A0E27' },
  homeScroll:          { padding: 20, paddingBottom: 40 },
  screenTitle:         { fontSize: 24, fontWeight: '700', color: '#fff', marginBottom: 20 },
  sectionTitle:        { fontSize: 14, fontWeight: '600', color: '#ffffff88', marginBottom: 12, marginTop: 4, textTransform: 'uppercase', letterSpacing: 1 },
  header:              { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 32 },
  headerSub:           { color: '#ffffff66', fontSize: 13, marginBottom: 4 },
  headerTitle:         { color: '#fff', fontSize: 26, fontWeight: '800', lineHeight: 32 },
  headerBadge:         { backgroundColor: '#2ED57322', padding: 10, borderRadius: 16 },
  gradCardOuter:       { borderRadius: 16, padding: 16, marginBottom: 12, overflow: 'hidden' },
  buttonWrapper:       { alignItems: 'center', justifyContent: 'center', height: 190, marginBottom: 24 },
  mainBtn:             { width: 150, height: 150, borderRadius: 75, alignItems: 'center', justifyContent: 'center', elevation: 12, shadowColor: '#5B8CFF', shadowOffset: { width: 0, height: 8 }, shadowOpacity: 0.5, shadowRadius: 16 },
  mainBtnText:         { color: '#fff', fontWeight: '700', fontSize: 14, textAlign: 'center', marginTop: 6 },
  statusCard:          { marginBottom: 16 },
  statusRow:           { flexDirection: 'row', alignItems: 'center', gap: 10 },
  statusDot:           { width: 10, height: 10, borderRadius: 5 },
  statusText:          { color: '#fff', fontSize: 14, fontWeight: '500', flex: 1 },
  statusSub:           { color: '#ffffff77', fontSize: 12, marginTop: 6 },
  helpCard:            { marginBottom: 24 },
  helpCardTitle:       { color: '#FF4757', fontSize: 15, fontWeight: '700', marginBottom: 4 },
  helpCardSub:         { color: '#ffffff88', fontSize: 12, marginBottom: 12 },
  helpBtn:             { backgroundColor: '#FF4757', padding: 14, borderRadius: 12, alignItems: 'center' },
  helpBtnText:         { color: '#fff', fontWeight: '800', fontSize: 16, letterSpacing: 1 },
  featureRow:          { flexDirection: 'row', gap: 10, marginBottom: 24, flexWrap: 'wrap' },
  featureCard:         { flex: 1, minWidth: (width - 60) / 4, backgroundColor: '#1A1F3A', borderRadius: 12, padding: 10, alignItems: 'center' },
  featureIcon:         { padding: 8, borderRadius: 10, marginBottom: 6 },
  featureLabel:        { color: '#fff', fontSize: 11, fontWeight: '600' },
  featureDesc:         { color: '#ffffff66', fontSize: 9, marginTop: 2, textAlign: 'center' },
  contactRow:          { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#1A1F3A', borderRadius: 12, padding: 12, marginBottom: 8 },
  contactAvatar:       { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  contactName:         { color: '#fff', fontSize: 14, fontWeight: '600' },
  contactSub:          { color: '#ffffff66', fontSize: 11, marginTop: 1 },
  emergencyBar:        { backgroundColor: '#FF4757', borderRadius: 12, padding: 16, alignItems: 'center', marginBottom: 16 },
  emergencyBarText:    { color: '#fff', fontWeight: '800', fontSize: 16, letterSpacing: 1 },
  emergencyCard:       { backgroundColor: '#1a0a0a', borderRadius: 16, padding: 16, marginBottom: 12, borderWidth: 0.5, borderColor: '#FF475733' },
  emergencyTitle:      { color: '#FF4757', fontSize: 20, fontWeight: '800', textAlign: 'center', marginBottom: 8 },
  emergencyMsg:        { color: '#ffffff99', fontSize: 13, textAlign: 'center' },
  emergencyCardHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  emergencyCardTitle:  { color: '#fff', fontSize: 15, fontWeight: '600' },
  alertSentBadge:      { flexDirection: 'row', alignItems: 'center', backgroundColor: '#2ED57322', borderRadius: 8, padding: 8, marginTop: 12 },
  alertSentText:       { color: '#2ED573', fontSize: 12, fontWeight: '500' },
  locText:             { color: '#ffffff99', fontSize: 13, marginBottom: 4 },
  mapBtn:              { flexDirection: 'row', alignItems: 'center', backgroundColor: '#5B8CFF', borderRadius: 8, padding: 10, marginTop: 8, justifyContent: 'center' },
  mapBtnText:          { color: '#fff', fontWeight: '600', fontSize: 13 },
  cameraPreview:       { backgroundColor: '#000', borderRadius: 12, height: 140, alignItems: 'center', justifyContent: 'center', gap: 10 },
  cameraText:          { color: '#ffffff66', fontSize: 12, textAlign: 'center' },
  emergencyActions:    { flexDirection: 'row', gap: 8, marginBottom: 12 },
  actionBtn:           { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, padding: 12, borderRadius: 12 },
  actionBtnText:       { color: '#fff', fontWeight: '700', fontSize: 11 },
  cancelBtn:           { borderWidth: 1, borderColor: '#FF4757', borderRadius: 12, padding: 14, alignItems: 'center', marginBottom: 20 },
  cancelBtnText:       { color: '#FF4757', fontWeight: '700', fontSize: 15 },
  alertItem:           { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#1A1F3A', borderRadius: 12, padding: 14, marginBottom: 8 },
  alertDot:            { width: 10, height: 10, borderRadius: 5 },
  alertType:           { color: '#fff', fontSize: 13, fontWeight: '700' },
  alertMsg:            { color: '#ffffff88', fontSize: 12, marginTop: 2 },
  alertTime:           { color: '#ffffff44', fontSize: 11, marginTop: 2 },
  statsRow:            { flexDirection: 'row', gap: 10 },
  statCard:            { flex: 1, backgroundColor: '#1A1F3A', borderRadius: 12, padding: 14, alignItems: 'center', borderTopWidth: 3 },
  statValue:           { fontSize: 28, fontWeight: '800' },
  statLabel:           { color: '#ffffff88', fontSize: 12, marginTop: 4 },
  profileAvatar:       { width: 52, height: 52, borderRadius: 26, backgroundColor: '#5B8CFF22', alignItems: 'center', justifyContent: 'center' },
  profileName:         { color: '#fff', fontSize: 16, fontWeight: '700' },
  profileSub:          { color: '#ffffff66', fontSize: 12, marginTop: 2 },
  settingRow:          { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#1A1F3A', borderRadius: 12, padding: 14, marginBottom: 8 },
  settingIcon:         { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  settingLabel:        { color: '#fff', fontSize: 14, fontWeight: '600' },
  settingDesc:         { color: '#ffffff66', fontSize: 11, marginTop: 2 },
  toggleTrack:         { width: 42, height: 24, borderRadius: 12, padding: 3, justifyContent: 'center' },
  toggleThumb:         { width: 18, height: 18, borderRadius: 9, backgroundColor: '#fff' },
  tabBar:              { backgroundColor: '#12162E', borderTopWidth: 0.5, borderTopColor: '#ffffff22', paddingTop: 6, height: 62 },
});