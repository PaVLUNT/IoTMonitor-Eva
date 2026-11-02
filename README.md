# IoT Cámara de Frío – Aplicación móvil Android (Kotlin)

**Asignatura:** TI3042 Aplicaciones Móviles para IoT – Unidad 2 (Evaluación sumativa, individual)
**Autor:** Pablo Allendes

> Nota: la problemática de la Unidad 1 no estaba en la guía; se asumió **monitoreo y control de temperatura de una cámara de frío industrial** (entorno OT). Ajustar este texto si el proyecto del equipo es otro.

## Paso 1 – Definición del proyecto

**Problemática:** en una cámara de frío (cadena de frío de alimentos/insumos) una desviación de temperatura no detectada genera pérdidas. Se requiere que un dispositivo en la cámara (nodo sensor) informe temperatura y humedad a un supervisor y que éste pueda activar el ventilador/enfriamiento a distancia.

**Dispositivos a conectar:** dos teléfonos Android con la misma APK:
| Rol | Función |
|---|---|
| Nodo sensor | Simula sensores (temperatura, humedad) y actuador (ventilador). Envía lecturas cada 2 s y ejecuta órdenes. |
| Panel de control | Muestra datos en tiempo real, alerta si T > 28 °C, y envía órdenes ON/OFF al ventilador. |

**Funcionalidades clave:** login con registro de usuarios; conexión inalámbrica entre 2 dispositivos; monitoreo en tiempo real; control remoto; almacenamiento y consulta de histórico (últimas 20 lecturas y promedio); medidas de seguridad IoT.

**Herramientas:** Android Studio, Kotlin, ViewBinding, Material Components, SQLite (`SQLiteOpenHelper`), API `android.bluetooth` (RFCOMM), `javax.crypto` (PBKDF2, HMAC-SHA256). Sin bibliotecas de terceros.

**Almacenamiento elegido: SQLite.** Justificación: el entorno OT (bodegas/cámaras) suele tener conectividad deficiente, por lo que se prioriza funcionamiento sin conexión; es parte estándar de Android (sin dependencias ni costos); las consultas se hacen con SQL parametrizado; los datos de sensores se guardan en un esquema simple y portable (ts, origen, temperatura, humedad, ventilador) fácil de exportar o sincronizar luego con Firebase/API MySQL en la Unidad 3-4.

## Paso 2 – Diseño

**Comunicación: Bluetooth Classic (RFCOMM).** Justificación: no requiere router ni infraestructura (la cámara puede no tener Wi-Fi); alcance de ~10 m suficiente para un supervisor cercano; bajo consumo; el emparejamiento aporta autenticación y cifrado del enlace. Wi-Fi/MQTT queda como evolución para largo alcance (Unidad 3-4).

**Autenticación: SQLite local** (usuario + contraseña). Justificación: funciona offline, no depende de un backend, seguridad adecuada con PBKDF2 + sal por usuario; Firebase/MySQL aportarían escalabilidad pero requieren red y servicios externos.

**Mockup (pantallas y navegación):**
```
 [ LOGIN ]                         [ PRINCIPAL ]
 +-------------------+             +------------------------------+
 | IoT Cámara de Frío|             | Hola, usuario        [Salir] |
 | Usuario  [______] |  Ingresar   | (o) Nodo sensor ( ) Panel    |
 | Clave    [______] | ----------> | Clave de enlace [******]     |
 | [Ingresar]        |             | [Esperar / Conectar a nodo]  |
 | [Crear cuenta]    |             | Estado: Conectado con ...    |
 | msg error         |             | 24.3 °C   55 %   Vent: OFF   |
 +-------------------+             | ⚠ ALERTA T > 28 °C           |
                                   | [Alternar ventilador]        |
                                   | Promedio histórico: 24.8 °C  |
                                   | 12:01:02 24.3°C 55% OFF [..] |
                                   | 12:00:58 24.1°C 54% OFF [..] |
                                   +------------------------------+
```
Flujo: Login → Pantalla principal → (elegir rol, clave de enlace) → conectar → monitoreo/control → Salir.

## Paso 3 – Construcción y seguridad (ISO/IEC 27400)

| Medida | Implementación |
|---|---|
| Cifrado de credenciales | PBKDF2-HMAC-SHA256, 120 000 iteraciones, sal aleatoria de 16 B (`PasswordHasher`). Nunca se guarda la contraseña en claro; comparación en tiempo constante. |
| Control de acceso | Login obligatorio; validación de entradas; bloqueo de 30 s tras 5 intentos fallidos; `exported=false` en MainActivity; `allowBackup=false`. |
| Permisos mínimos | Solo permisos Bluetooth; `BLUETOOTH_CONNECT` solicitado en tiempo de ejecución (Android 12+). Sin ubicación ni Internet. |
| Conexión segura | Socket RFCOMM seguro (autenticado y cifrado por emparejamiento). Adicionalmente cada mensaje lleva HMAC-SHA256 con clave derivada de la clave de enlace (`SecureCodec`): rechaza mensajes manipulados o de dispositivos sin la clave. |
| Anti-replay | Timestamp creciente por emisor; mensajes repetidos se descartan. |
| Robustez | Límite de largo de línea, parseo con manejo de errores, SQL parametrizado. |

## Paso 3 – Plan de pruebas (ejecutar con 2 teléfonos)
1. **Login:** crear cuenta; ingresar con credenciales correctas (OK) e incorrectas (mensaje); 5 fallos → bloqueo 30 s.
2. **Conexión:** emparejar ambos teléfonos en Ajustes > Bluetooth. Teléfono A: rol *Nodo sensor*, clave 123456, "Esperar conexión". Teléfono B: rol *Panel*, misma clave, "Conectar a nodo vinculado" y elegir A.
3. **Datos:** el panel muestra temperatura/humedad cada 2 s; ambos guardan lecturas (ver lista y promedio).
4. **Control:** con T > 28 °C aparece alerta; pulsar "Alternar ventilador" en el panel → el nodo cambia a ON y la temperatura baja.
5. **Seguridad:** usar claves de enlace distintas → "Mensaje rechazado (HMAC inválido)".

## Paso 4 – Demostración
Mostrar: login/registro; conexión de los 2 dispositivos; datos y control en vivo; consulta del histórico SQLite; y las medidas de seguridad de la tabla anterior.

## Compilar
Abrir la carpeta en Android Studio (Koala o superior, JDK 17), esperar la sincronización de Gradle, conectar los teléfonos y ejecutar `app`. APK de depuración: *Build > Build APK(s)*.
