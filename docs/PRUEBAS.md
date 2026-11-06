# Verificación de la actualización

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

La segunda parte abre Inicio, oculta y vuelve a mostrar montos, abre Meses, busca `cafe` y encuentra `Café`, filtra pendientes, consulta Cuentas y vuelve a comprobar que el contenido financiero no cambió.

Las capturas se guardan en `build/qa/` y solo contienen datos inventados del emulador. Nunca ejecutes estas instrucciones contra un celular ni uses un respaldo personal como fixture.

## Alcance

Una prueba en emulador no garantiza el comportamiento de todos los fabricantes. La importación SMS, el acceso a notificaciones y las restricciones de batería dependen del dispositivo y de sus permisos. La preservación al instalar en otro equipo depende de conservar el mismo identificador y certificado; consulta [Actualización](ACTUALIZACION.md).
