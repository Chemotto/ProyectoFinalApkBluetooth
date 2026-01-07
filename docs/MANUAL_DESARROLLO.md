# Manual de Desarrollo: Control LED Bluetooth

Este documento describe paso a paso cómo se ha desarrollado el proyecto **ProyectoFinalApkBluetooth**, una aplicación Android para controlar tiras LED RGB mediante Bluetooth (Clásico y BLE).

---

## 1. Configuración del Entorno

### 1.1 Estructura del Proyecto
*   **IDE**: Android Studio
*   **Lenguaje**: Kotlin
*   **API Mínima**: Android 6.0 (API 23)
*   **Build System**: Gradle con Kotlin DSL

### 1.2 Dependencias Principales (`build.gradle.kts`)
Se han incluido las siguientes librerías para facilitar el desarrollo de la interfaz y la funcionalidad:
*   `androidx.core:core-ktx`: Extensiones de Kotlin para Android.
*   `androidx.appcompat:appcompat`: Compatibilidad con versiones antiguas de Android.
*   `com.google.android.material:material`: Componentes de diseño Material Design (botones, chips, sliders).
*   `androidx.constraintlayout:constraintlayout`: Gestión flexible de layouts.
*   `androidx.recyclerview:recyclerview`: Listas eficientes para dispositivos Bluetooth.

---

## 2. Permisos y Manifiesto

Para acceder al Bluetooth y al Micrófono (para el modo audio), se configuró el `AndroidManifest.xml` con dos bloques de permisos diferenciados por versión de Android:

*   **Android 11 o inferior**:
    *   `BLUETOOTH` y `BLUETOOTH_ADMIN`: Para conectar y descubrir.
    *   `ACCESS_FINE_LOCATION`: Necesario para escanear dispositivos BLE.
*   **Android 12 o superior**:
    *   `BLUETOOTH_SCAN`: Para buscar dispositivos.
    *   `BLUETOOTH_CONNECT`: Para establecer conexión.
*   **Audio**: `RECORD_AUDIO` para el modo reactivo musical.

---

## 3. Interfaz de Usuario (UI)

La interfaz se diseñó en un único `Activity` (`MainActivity`) usando XML.

### 3.1 Componentes Clave (`activity_main.xml`)
*   **ConstraintLayout**: Contenedor principal.
*   **Chip de Estado (`chipStatus`)**: Muestra "Desconectado", "Conectando..." o "Conectado".
*   **Rueda de Color (`ColorWheelView`)**: Una vista personalizada (`View`) que dibuja un gradiente RGB circular y detecta toques para seleccionar colores.
*   **Lista de Dispositivos (`RecyclerView` dentro de `CardView`)**: Muestra los dispositivos encontrados. Se oculta cuando hay conexión establecida.
*   **Controles de Modo**: Botones para efectos (Flash, Strobe, Fade) y botón de encendido.
*   **Modo Música**: Botón inferior para activar el micrófono.

### 3.2 Adaptador de Lista (`BluetoothDeviceAdapter.kt`)
*   Gestiona la visualización de dispositivos escaneados.
*   Identifica dispositivos guardados mostrando una estrella (⭐).
*   Permite renombrar dispositivos con una pulsación larga, guardando el alias en `SharedPreferences`.

---

## 4. Lógica Bluetooth

Esta es la parte más compleja del sistema, dividida en dos estrategias.

### 4.1 Servicio Bluetooth (`BluetoothService.kt`)
Clase encargada de manejar la conexión en segundo plano para evitar bloquear la interfaz.
*   **Hilos**:
    *   `ConnectThread`: Intenta establecer el socket Bluetooth.
    *   `ConnectedThread`: Mantiene la conexión abierta y gestiona la entrada/salida de datos.
*   **Estrategias de Conexión**:
    1.  **UUID Seguro**: Intenta conectar con el perfil SPP estándar.
    2.  **UUID Inseguro**: Fallback si el primero falla.
    3.  **UUIDs Específicos**: Busca UUIDs propietarios (como el de chips HM-10).
    4.  **Reflexión**: Método de fuerza bruta para dispositivos antiguos.

### 4.2 Lógica en `MainActivity.kt`
*   **Escaneo**: Utiliza `bluetoothAdapter.startDiscovery()`.
*   **Conexión Dual**: Detecta si el dispositivo es Clásico o BLE.
    *   **Clásico**: Usa `BluetoothService`.
    *   **BLE**: Usa `BluetoothGatt`.
*   **Manejo de Errores**: Implementa reintentos automáticos para el error "GATT 133" y pausas estratégicas (300ms) para no saturar el chip Bluetooth.

---

## 5. Protocolo de Comunicación

La app envía comandos en formato de bytes hexadecimales compatibles con controladores **Magic Home**.

*   **Cambio de Color**: `0x56 R G B 00 F0 AA`
*   **Efectos**: `0xBB [Modo] [Velocidad] 0x44`
*   **Encendido/Apagado**: Envía color Negro (`0,0,0`) para apagar y restaura el último color para encender.

---

## 6. Funcionalidad de Audio Reactivo

### 6.1 Captura de Audio
*   Utiliza `AudioRecord` para capturar muestras PCM del micrófono en tiempo real.

### 6.2 Procesamiento (FFT)
*   Implementa un algoritmo **Fast Fourier Transform (FFT)** manual.
*   Convierte la señal de tiempo a frecuencia.
*   Divide las frecuencias en tres bandas: Bajos (Bass), Medios y Agudos.
*   Mapea la intensidad de cada banda a un canal RGB (Bajos -> Rojo, Medios -> Verde, Agudos -> Azul).

---

## 7. Persistencia de Datos

Utiliza `SharedPreferences` ("LedControlPrefs") para:
*   Guardar la dirección MAC de los dispositivos conectados exitosamente.
*   Almacenar nombres personalizados de los dispositivos.
*   Recordar los colores favoritos guardados por el usuario.

---

## 8. Flujo de Uso

1.  **Inicio**: La app verifica permisos y carga dispositivos guardados.
2.  **Escaneo**: El usuario pulsa "Escanear". Aparece la lista.
3.  **Conexión**: Al pulsar un dispositivo, se detiene el escaneo y se intenta conectar (con reintentos si es necesario).
4.  **Control**: Una vez conectado, se habilita la rueda de color y los modos.
5.  **Desconexión**: El botón cambia a "Desconectar", liberando recursos y volviendo al estado inicial.
