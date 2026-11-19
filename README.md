# Cadena de Frío - Cold Chain Monitor v2.0

Aplicación Android para el monitoreo térmico en tiempo real y control operativo de la cadena de frío (refrigeración y conservación), con integración IoT mediante **Wi-Fi**, **Bluetooth Classic (RFCOMM/SPP)** y **Modo Simulación**.

---

## 🔐 Credenciales de Acceso y Perfiles RBAC

Por motivos de seguridad e higiene de interfaz, las credenciales han sido retiradas de la pantalla de inicio de sesión de la aplicación. A continuación se detallan los perfiles configurados en la base de datos local SQLite (las contraseñas se almacenan mediante **Hash SHA-256**):

| Rol | Correo Electrónico | Contraseña | Permisos y Alcance Operativo |
| :--- | :--- | :--- | :--- |
| **SUPERVISOR** | `supervisor@inacap.cl` | `Sup12345!` | **Control Total**: Monitoreo en vivo, historial, conexión BT/Wi-Fi, modo emisor y envío de todos los comandos remotos (`CMD:SILENCIO`, `CMD:AUTO`, `CMD:TEST`). |
| **MANTENEDOR** | `mantenedor@inacap.cl` | `Man12345!` | **Pruebas y Mantenimiento**: Monitoreo, historial, conexión BT/Wi-Fi, modo emisor y comandos de diagnóstico (`CMD:AUTO`, `CMD:TEST`). |
| **OPERADOR** | `operador@inacap.cl` | `Ope12345!` | **Solo Lectura**: Monitoreo en vivo e historial de telemetría. Comandos y conexiones avanzadas deshabilitados. |

---

## 🛡️ Políticas de Seguridad de Autenticación

* **Límite de Intentos:** Máximo 3 intentos fallidos consecutivos de contraseña.
* **Bloqueo Permanente:** Al alcanzar los 3 intentos fallidos, la cuenta queda bloqueda permanentemente (`bloqueado_hasta = -1L`).
* **Desbloqueo vía Gmail:** Al bloquearse la cuenta, la aplicación habilita la opción **"Desbloquear vía Gmail"**, la cual genera una solicitud por correo electrónico preconfigurada para restablecer el acceso al operador.

---

## 📡 Conectividad y Protocolos IoT

1. **Wi-Fi (HTTP Polling):**
   * Petición de telemetría: `GET http://<IP_NODO>/api/telemetria` (intervalo de 2 segundos).
   * Envío de comandos: `POST http://<IP_NODO>/api/comando` (cuerpo en texto plano).
2. **Bluetooth Classic (RFCOMM / SPP):**
   * UUID Estándar SPP: `00001101-0000-1000-8000-00805F9B34FB`.
   * Nombre del Servicio: `ColdChainNodoOT`.
   * Formato de mensajes: Tramas JSON de `TelemetriaModel` finalizadas en `\n`.
3. **Modo Nodo Emisor (Emulador en Teléfono):**
   * Permite usar un dispositivo Android como servidor/nodo IoT que genera datos térmicos reales/simulados mediante deslizador (SeekBar) y transmisor Bluetooth.

---

## ⚙️ Requisitos Técnicos

* **Android Studio:** Hedgehog / Iguana / Jellyfish o superior.
* **Min SDK:** 26 (Android 8.0 Oreo).
* **Target SDK:** 34 (Android 14).
* **Lenguaje:** Kotlin 1.9+.
