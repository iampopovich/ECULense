plugins {
    id("ecu.android.library")
}

// AES-GCM secrets with an Android Keystore key (LLM API keys in :core:ai, the MQTT broker password in :feature:mqtt).
// Each user picks its own key alias; the key never leaves the device and is not part of any backup.
dependencies {
}
