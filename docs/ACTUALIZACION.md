# Actualizar conservando los datos

## Antes de instalar

1. En la app actual, abre **Cuentas → Respaldo** (en versiones anteriores: **Guardar / recuperar copia de seguridad**).
2. Guarda el JSON en una ubicación privada. Contiene información financiera en texto plano; no lo adjuntes a GitHub ni lo compartas en incidencias.
3. Conserva la instalación actual. **No desinstales, no borres almacenamiento y no uses `adb uninstall` ni `pm clear`.**

## Compatibilidad de 0.5.1

| Propiedad | Valor |
| --- | --- |
| Identificador Android | `gt.finanzas.privadas` |
| Base de datos | `mis_finanzas.db` |
| Versión del esquema SQLite | `1`, sin migraciones |
| VersionCode | `8` (anterior: `7`) |
| VersionName | `0.5.1` |
| Formato de respaldo | `mis-finanzas-private-v1`, sin cambios |

La actualización modifica la presentación y agrega preferencias de visualización. No ejecuta reclasificaciones, importaciones ni restauraciones automáticas nuevas. Se mantiene la sincronización ya elegida por la persona, que puede importar SMS al abrir la app si estaba activada.

La navegación principal ahora incluye **Asistente** y llama **Reportes** a la sección mensual. Las herramientas, pagos, presupuestos e importación están disponibles mediante el icono de ajustes de la cabecera. El asistente y las nuevas gráficas solo leen registros; sus simulaciones no modifican saldos.

La instalación debe usar **el mismo certificado que la app existente**. La firma de depuración depende del equipo/perfil de Android Studio. Una compilación de otro equipo o de GitHub Actions tendrá normalmente una firma diferente y no servirá para actualizar esta instalación.

## Instalar

Desde Android Studio, selecciona el mismo perfil de firma usado anteriormente, conecta el dispositivo y ejecuta `app`. También puedes abrir en el teléfono el APK generado localmente. Android debe ofrecer **Actualizar**.

Si Android indica firma incompatible o rechaza la instalación, detente y usa la clave original. **No resuelvas el problema desinstalando.** El repositorio nunca debe contener claves de firma.

## Verificar después

- Revisa que aparezcan tus cuentas y saldos anteriores.
- Busca varios movimientos antiguos y comprueba sus fechas y categorías.
- Revisa presupuestos, pagos y preferencias de sincronización.
- Abre **Meses** y consulta un mes anterior.
- Guarda otro respaldo privado cuando termines.

Los importes siguen siendo estimaciones basadas en lo registrado. Los reportes no sustituyen un estado de cuenta bancario.
