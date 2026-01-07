# Control LED Bluetooth - Magic Home / BLE

Una aplicación Android moderna desarrollada en Kotlin para controlar tiras LED RGB mediante Bluetooth (tanto Clásico como Low Energy - BLE). Diseñada para ser compatible con controladores genéricos tipo Magic Home, HM-10, MLT-BT05 y dispositivos SPP estándar.

## 📱 Características Principales

*   **Conexión Dual**: Soporte transparente para Bluetooth Clásico y Bluetooth Low Energy (BLE).
*   **Escaneo y Gestión**:
    *   Escáner de dispositivos cercanos.
    *   Guardado automático de dispositivos conectados recientemente.
    *   Identificación visual (estrella) para dispositivos guardados.
    *   **Renombrado personalizado**: Mantén pulsado un dispositivo guardado para asignarle un nombre propio.
*   **Control de Color**:
    *   Rueda de color interactiva para selección RGB precisa.
    *   Control de brillo global.
    *   4 botones de acceso rápido a colores favoritos (personalizables con pulsación larga).
*   **Efectos**:
    *   Modos predefinidos: Flash (Continuo), Strobe (Fiesta) y Fade (Suave).
    *   Botón de Encendido/Apagado con memoria de estado.
*   **Modo Audio Reactivo**:
    *   Utiliza el micrófono del móvil para analizar el sonido ambiente.
    *   Transforma la música en luces rítmicas mediante análisis FFT en tiempo real.
    *   Ajuste dinámico de sensibilidad.

## 🛠️ Tecnologías Utilizadas

*   **Lenguaje**: Kotlin
*   **UI**: XML Layouts, ConstraintLayout, Material Design Components.
*   **Bluetooth**:
    *   `BluetoothAdapter` & `BluetoothGatt`
    *   Manejo robusto de errores de conexión (incluyendo el famoso Error 133 de Android).
    *   Estrategias de conexión múltiples (UUID seguro, inseguro, reflexión).
*   **Audio**: `AudioRecord` y procesamiento FFT manual para visualización.
*   **Persistencia**: `SharedPreferences` para guardar dispositivos y configuraciones.

## 🚀 Instalación y Uso

1.  **Permisos**: Al iniciar, la app solicitará permisos de Ubicación (necesario para escanear BLE en Android < 12) o Bluetooth Scan/Connect (Android 12+).
2.  **Conectar**:
    *   Pulsa "Escanear" para buscar tu controlador LED.
    *   Selecciona el dispositivo de la lista.
    *   Si es un dispositivo conocido, aparecerá con una estrella ⭐.
3.  **Controlar**:
    *   Usa la rueda para cambiar el color.
    *   Los botones inferiores activan modos especiales.
    *   Mantén pulsado uno de los 4 círculos de color para guardar el color actual como favorito.
4.  **Audio**:
    *   Pulsa "Audio Reactivo" para que las luces bailen al ritmo de la música. (Requiere permiso de micrófono).

## 🐛 Solución de Problemas Comunes

*   **No conecta (Error 133)**: La app tiene un sistema de reintento automático. Si persiste, reinicia el Bluetooth de tu móvil y vuelve a intentarlo.
*   **Dispositivo no aparece**: Asegúrate de que el controlador LED está encendido y no está conectado a otro móvil.
*   **Desconexión lenta**: La interfaz se actualiza inmediatamente, pero el hardware puede tardar unos segundos en liberar el canal.

## 📝 Licencia

Este proyecto es de uso libre para fines educativos y personales.