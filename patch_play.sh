sed -i 's/AudioService.player?.stop()/AudioService.player?.stop(); AudioService.player?.clearMediaItems()/' /opt/data/rythmix-native/app/src/main/java/com/haviq/richmusic/MainActivity.kt
