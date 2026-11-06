# Privacidad y publicación

La aplicación almacena cuentas, movimientos, texto bancario reconocido, presupuestos y pagos en SQLite dentro del espacio privado de Android. No solicita credenciales bancarias ni declara permiso `INTERNET`.

El permiso SMS es opcional. El acceso a notificaciones es una autorización separada del sistema. Las reglas descartan mensajes identificados como credenciales o códigos; no constituyen una garantía absoluta ante todo formato posible.

La base de datos no tiene cifrado propio. Los respaldos JSON y los reportes CSV son archivos sensibles sin cifrado. Protege el dispositivo y los archivos con sus controles de seguridad.

**Ocultar Q** enmascara importes y oculta las gráficas de reportes en pantalla. No cifra datos, no oculta nombres de comercios o cuentas, y no modifica los archivos que exportas. Los campos de edición muestran los importes que decides editar. No publiques capturas de tus datos reales.

Desde 0.5.0 este control es el icono del ojo. Las respuestas del asistente se calculan localmente y se vuelven a enmascarar al ocultar los montos. El texto que escribes, los comercios y las categorías siguen visibles. La conversación se mantiene durante la sesión (incluida la recreación de la actividad por Android), no se agrega a la base de movimientos y se puede limpiar desde la propia pantalla. No hay llamadas a proveedores de IA. El simulador de ahorro no guarda cambios financieros.

El repositorio contiene código, recursos de la app, documentación y pruebas inventadas. Los ejemplos de cuentas `1111` y `2222`, comercios DEMO, fechas e importes son ficticios. No son un historial personal anonimizado.

`.gitignore` excluye bases de datos, respaldos JSON, CSV, hojas de cálculo, APK, claves de firma, archivos de entorno, cachés y configuración local. Una exclusión no elimina archivos ya versionados: revisa siempre `git diff --cached` y `git ls-files` antes de publicar.

Para reportar un error, usa un ejemplo inventado y elimina números de cuenta, códigos, SMS originales y capturas reales.
