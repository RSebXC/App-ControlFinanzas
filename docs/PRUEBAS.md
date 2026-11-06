# Verificación de la actualización

## Resultado de 0.5.1

- APK compilado y Android lint completado sin errores; continúan advertencias de compatibilidad y presentación ya descritas.
- Veintidós pruebas unitarias aprobadas, incluidas quince del planificador: aportes mensuales y totales, centavos, extras, retiros, metas, datos faltantes, preguntas de seguimiento, separadores inválidos y protección contra desbordamiento.
- En el emulador se verificó la secuencia ficticia de Q1,000 iniciales + Q500 al mes durante 2 meses → Q2,000; cambio a 3 meses → Q2,500; retiro único Q200 → Q2,300.
- Comprobados el enmascarado de respuestas, ocultación de gráficas, aclaración de ahorro inicial y conservación del contexto al superar ocho mensajes visibles.
- La exportación completa de la base se mantuvo idéntica después de navegar y realizar las simulaciones.
- Actualización autorizada del celular desde 0.5.0: respaldo privado, misma firma y archivos de base de datos y preferencias idénticos antes y después de instalar. La actividad principal quedó al frente después de desbloquear el dispositivo.

Las proyecciones son hipotéticas y no guardan movimientos. La captura pública de ahorro usa solo datos inventados del emulador.

## Resultado de 0.5.0

- APK compilado correctamente y Android lint completado sin errores (hay advertencias sobre recursos, internacionalización, dibujo y APIs obsoletas).
- Siete pruebas unitarias aprobadas: formatos bancarios, cálculos de reportes, gasto acumulado con devoluciones, meses con año bisiesto y consultas por fechas explícitas o relativas.
- Conservación de la exportación completa de la base al actualizar en emulador desde la versión anterior; esquema SQLite 1 sin migraciones.
- Agregaciones diarias de solo lectura comparadas con los totales mensuales; mayor compra y principales comercios comprobados con datos ficticios.
- Verificados Inicio, indicadores visibles, privacidad, modos líneas/barras, período de 12 meses, dona, búsqueda, filtros, cuentas y respuestas del asistente.
- El envío desde el campo fijo del asistente y el enmascarado de respuestas anteriores fueron comprobados automáticamente.
- Capturas revisadas visualmente para corregir la distribución de indicadores y documentar el rediseño.
- Actualización autorizada en el celular a 0.5.0: respaldo privado antes de instalar, certificado coincidente y todos los archivos de base de datos y preferencias idénticos antes y después de instalar, comprobados antes de abrir la app. La actividad principal se abrió correctamente.

Los respaldos y comprobantes locales del dispositivo están excluidos de Git. Las capturas públicas proceden exclusivamente del emulador.

## Resultado de 0.4.0

- APK de depuración compilado correctamente.
- Cuatro pruebas unitarias aprobadas, con múltiples comprobaciones de formatos bancarios y cálculos de reportes.
- Android lint completado sin errores; conserva advertencias sobre recursos y APIs obsoletas.
- Actualización desde el APK 0.3.2 a 0.4.0 verificada en emulador: mismo hash de exportación completa antes y después, misma cantidad de movimientos y mismo esquema SQLite.
- Navegación, ocultación de importes, búsqueda sin tildes, filtro de pendientes y consulta de cuentas verificadas automáticamente.
- Certificados del APK anterior y de la compilación local nueva comparados y coincidentes. Esto no garantiza que compilaciones hechas en otros equipos usen esa firma.
- Capturas revisadas visualmente con datos inventados. No se instaló ni se modificó la app del celular durante estas pruebas.

## Pruebas locales

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Las pruebas unitarias verifican formatos bancarios inventados, importes en centavos, mensajes descartados por privacidad, selección de meses al cruzar años, porcentajes sin división por cero y devoluciones.

Los reportes quedan en `app/build/reports/`. No se versionan: pueden contener rutas e identificadores del equipo.

## Prueba real de actualización en emulador

`UpgradeSmokeTest` es una instrumentación de Android sin dependencias externas. Solo permite ejecutarse en hardware de emulador y exige el argumento `allowSyntheticData=emulator-only`.

La preparación requiere una base vacía; se detiene si ya existen cuentas o movimientos. No borra datos. Crea cuentas, movimientos de varios meses, una devolución superior a las compras, un mes sin registros, un pendiente, un presupuesto y un pago, todos ficticios.

1. Inicia un emulador desechable vacío.
2. Conserva privadamente un APK de la versión anterior firmado con la misma clave.
3. Configura `JAVA_HOME` con JDK 17 o 21 y agrega `platform-tools` al PATH, o pasa la ruta de `adb`.
4. Ejecuta:

```powershell
.\tests\run_upgrade_smoke.ps1 -PreviousApk '<ruta privada al APK anterior>' -Serial emulator-5554
```

El script instala la versión anterior, prepara datos ficticios y guarda el SHA-256 de la exportación completa. Después instala la versión nueva con `install -r` y compara la exportación completa y la cantidad de movimientos, además de verificar la versión SQLite 1.

La segunda parte abre Inicio, comprueba sus indicadores, oculta y muestra montos, abre Reportes, cambia modos y períodos, consulta la dona, busca `cafe` y encuentra `Café`, filtra pendientes y consulta Cuentas. También realiza preguntas al asistente, envía una consulta escrita, enmascara respuestas y vuelve a comprobar que el contenido financiero no cambió.

Las capturas se guardan en `build/qa/` y solo contienen datos inventados del emulador. Nunca ejecutes estas instrucciones contra un celular ni uses un respaldo personal como fixture.

## Alcance

Una prueba en emulador no garantiza el comportamiento de todos los fabricantes. La importación SMS, el acceso a notificaciones y las restricciones de batería dependen del dispositivo y de sus permisos. La preservación al instalar en otro equipo depende de conservar el mismo identificador y certificado; consulta [Actualización](ACTUALIZACION.md).
