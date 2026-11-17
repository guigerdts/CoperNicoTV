# ADR-0007 — Cifrado del catálogo: clave de datos de sesión protegida por la raíz de Keystore (envolvente 0x02)

Status: accepted

## Contexto

El catálogo persiste URL de stream y logos cifrados con AES-GCM. La envolvente 0x01 cifraba **cada registro directamente con la clave de Keystore** (`ClavesApp`, alias no exportable). La medición en dispositivo físico (146.762 canales importados) mostró el cuello de botella: 293.521 operaciones AES contra Keystore, 1.723.371 ms de cifrado y 43.252 ms de inserción Room — un coste de ≈5,87 ms por registro, dominado por el coste por operación de Keystore. La latencia es O(canales) en operaciones de Keystore y no puede reducirse con ese diseño.

Requisitos de la ronda 8 (E7-T7):

- Mantener la compatibilidad con datos existentes en 0x01 (legibles, sin migración destructiva).
- Reducir las operaciones de Keystore a O(1) por sesión de importación.
- Sin claves hardcodeadas ni secretos en claro; sin material criptográfico en logs.
- Migración idempotente, atómica y no destructiva; conservar la recuperación pendiente.

## Decisión

**Envolvente 0x02: clave de datos de sesión protegida (wrapped) por la raíz de Keystore.**

Formato: `CifradoAes` (`:core`) introduce `VERSION_DATOS = 0x02` con el mismo layout por registro (1 byte de versión + IV único de 12 bytes + ciphertext + tag GCM) y AAD por campo dominio-separated (`CifradoAes.aad(...)`). El byte de versión es el primer byte del sobre y despacha el descifrado.

Ciclo de vida de la clave de datos (`ClavesDatos`, `:app`):

1. **Generación**: clave AES-256 de 32 bytes generada por CSPRNG del sistema, en memoria por sesión.
2. **Protección (envolver)**: se cifra **una sola vez** con la clave raíz de Keystore usando la envolvente 0x01 (una operación de Keystore). El resultado de esa envuelta es lo único que sale del proceso: se persiste en un archivo dentro de `noBackupFilesDir` con escritura síncrona atómica (archivo temporal + rename). El almacenamiento (`AlmacenClaveDatos`) es una interfaz síncrona con implementación de archivo, testeable con fakes.
3. **Desprotección (desenvolver)**: al iniciar una sesión que escribe o lee datos, la clave envuelta se desenvuelve con la raíz (una operación de Keystore) y la clave de datos vive en memoria. Si la desprotección falla (raíz inválida, archivo corrupto), se **regenera** la clave de datos: los datos previos quedan ilegibles hasta que el usuario reimporte la lista — **nunca se borran datos**, y el estado de recuperación pendiente se conserva (mismo contrato de recuperación que 0x01).
4. **Zeroización honesta**: los buffers de material intermedio se ponen a cero con `fill(0)` tras usarlos. Limitación documentada: la JVM no garantiza zeroización fiable de copias internas de `SecretKeySpec`; el material vive el mínimo tiempo posible y nunca se registra en logs.
5. **IV único**: cada operación AES-GCM genera un IV nuevo; nunca se reutiliza con la misma clave.

Lecturas: `descifrarSobre(claveRaiz, claveDatos, aad, sobre)` despacha por byte de versión — 0x01 usa la clave raíz de Keystore por registro (compatibilidad completa), 0x02 usa la clave de datos de sesión. `descifrar` (sin sobre de versión) queda **estricto 0x01** y se usa solo para la envuelta de la clave de datos; así un dato nuevo jamás se escribe por accidente con 0x01.

Escrituras: toda escritura nueva de datos del catálogo usa 0x02. Las operaciones de Keystore por importación quedan en **O(1)** (generación o desprotección de la clave de datos + protección si corresponde), independientes del número de canales.

**Migración**: la importación **es** la migración. `escribirCatalogo` reescribe el catálogo completo dentro de una única transacción Room: idempotente (reimportar la misma lista produce el mismo estado), atómica (una interrupción no deja un catálogo a medias) y no destructiva (nunca se eliminan datos sin haber verificado el nuevo cifrado; los datos 0x01 se conservan legibles hasta que una reimportación los reescriba en 0x02). No existe un job de migración en segundo plano ni conversión en línea durante lecturas.

**Modelo de amenazas**:

- **Atacante con acceso al sistema de archivos (dispositivo rooteado, backup, APK clonado)**: obtiene los registros 0x02 y la clave envuelta, pero sin la raíz de Keystore (no exportable, ligada al dispositivo) no puede desenvolver la clave de datos. Igual que 0x01 en protección por registro, pero con una sola envuelta que proteger.
- **Atacante con acceso solo al disco (sin Keystore)**: los datos en reposo permanecen cifrados; la clave de datos nunca se persiste en claro y la envuelta es inútil sin la raíz.
- **Extracción de la clave de datos en memoria**: la clave vive solo durante la sesión del proceso; los buffers intermedios se ponen a cero; no se registra en logs ni informes (los campos del informe son métricas, sin material).
- **Atacante que modifica datos en reposo (tamper)**: AES-GCM autentica; un sobre alterado falla la verificación y la lectura devuelve error tipado — no se entrega contenido no autenticado.
- **Corrupción de la envuelta / pérdida de la raíz** (cambio de dispositivo, restore en otro equipo, reinstalación con borrado de Keystore): la desprotección falla → regeneración de la clave de datos y recuperación pendiente; el usuario reimporta. El catálogo no se destruye silenciosamente y la app no entra en bucle de fallos.
- **Fuera de alcance**: protección contra atacante con ejecución de código dentro del proceso (la memoria del proceso contiene la clave en claro durante la sesión — igual que cualquier uso de Keystore con datos en memoria); protección de la raíz contra el propio usuario del dispositivo con el dispositivo desbloqueado; DRM de streams.

**Instrumentación separada** (S5): el informe de importación distingue ops de Keystore (ciclo de la clave), ops AES de cifrado y descifrado, tiempo de generación/protección/desprotección de la clave, y sobres escritos por versión (`sobre_0x01`/`sobre_0x02`); los tiempos de Room (inserción, lotes, transacciones) se reportan por separado de los criptográficos.

## Consecuencias

- Rendimiento esperado: el tiempo de cifrado por registro baja de ≈5,87 ms (Keystore) al coste de AES-GCM en software (varios órdenes de magnitud menos); la medición real se hace en dispositivo físico antes de declarar el problema resuelto (S8).
- Compatibilidad: los datos 0x01 existentes siguen legibles por despacho; las pruebas SIEMPRE cubren semillas 0x01.
- Riesgo aceptado: una sola clave protege todo el catálogo durante la sesión (un solo target por atacante con memoria del proceso); a cambio, cada registro deja de costar una operación de Keystore.
- Pruebas obligatorias (S6): legibilidad 0x01, escritura/lectura 0x02, IV distinto por operación, tamper detectado, clave de datos incorrecta, raíz inválida, migración interrumpida y reanudada, migración repetida, recuperación tras reinicio del proceso, sin claves en logs, e importación masiva de 146.762 canales.

## Opciones consideradas

- **Mantener 0x01 por registro**: descartado — el coste por operación de Keystore hace que la importación masiva tarde ~29 minutos solo en cifrado, medido en dispositivo físico.
- **Clave de datos persistida en claro (o en SharedPreferences sin envolver)**: descartado — viola el requisito de sin secretos en claro y anula la protección de la raíz.
- **Clave de datos persistida con la raíz por registro (envolver cada registro con la raíz, igual a 0x01)**: descartado — no reduce las operaciones de Keystore.
- **Reencriptación en línea durante lecturas (migración lazy por registro)**: descartada — mezcla el camino de lectura (reproducción) con escrituras y deja estados mixtos difíciles de razonar; la reimportación única es idempotente y atómica.
- **Keystore para envolver con clave efímera por importación sin persistencia**: descartado — los datos persistidos quedarían ilegibles al reiniciar el proceso sin reimportación completa cada vez.
