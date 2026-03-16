# Omnia2 - Wear OS Audio & IoT Companion

Omnia2 est une application sophistiquée conçue pour **Wear OS**, offrant des fonctionnalités avancées d'enregistrement audio, de géolocalisation et d'intégration IoT. Elle permet aux utilisateurs de capturer des mémos vocaux avec des métadonnées précises et de les synchroniser de manière transparente avec un stockage Cloud.

## 🚀 Fonctionnalités Clés

- 🎙️ **Enregistrement Audio** : Capture audio haute qualité au format MPEG-4 (AAC).
- 📍 **Géolocalisation** : Association automatique des coordonnées GPS (Latitude/Longitude) à chaque enregistrement via un fichier JSON de métadonnées.
- ☁️ **Synchronisation Cloud** : Téléchargement automatique et manuel vers un serveur compatible **Amazon S3** (MinIO, AWS, etc.).
- 📡 **Messagerie MQTT** : Réception de messages en temps réel via le protocole MQTT, avec gestion de session persistante pour ne manquer aucune information.
- ⌚ **Interface Optimisée** : UI moderne construite avec **Compose for Wear OS**, incluant la navigation par balayage (HorizontalPager) et le support des **Tuiles (Tiles)** et **Complications**.
- 🔄 **Gestion du Stockage** : Consultation et lecture des fichiers locaux et distants.

## 🛠️ Architecture du Projet

Le projet suit une structure modulaire pour une meilleure maintenabilité :

- `com.example.omnia2.presentation` : Gère l'interface utilisateur, les écrans de navigation (`RecordScreen`, `PlayScreen`, `SyncScreen`, `MqttScreen`) et le cycle de vie de l'application.
- `com.example.omnia2.data` : classes utilitaires pour les services externes :
    - `MqttManager` : Gère la connexion au broker HiveMQ et le flux de messages.
    - `S3Manager` : Gère les opérations de transfert et de listing des objets S3.
    - `S3Config` : Configuration des points de terminaison et des identifiants (utilisez des variables d'environnement en production).
- `com.example.omnia2.tile` & `com.example.omnia2.complication` : Implémentations pour l'accès rapide depuis le cadran de la montre.

## ⚙️ Configuration

### Permissions Requises
L'application nécessite les permissions suivantes pour fonctionner correctement :
- `RECORD_AUDIO` : Pour l'enregistrement des mémos.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION` : Pour le marquage géographique.
- `INTERNET` & `ACCESS_NETWORK_STATE` : Pour MQTT et S3.


---
*Développé pour offrir une expérience fluide et robuste au poignet.*
