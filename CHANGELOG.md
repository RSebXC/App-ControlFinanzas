# Cambios

## 0.5.0

- Nueva marca vectorial y un sistema consistente de iconos, colores, tarjetas y botones con respuesta táctil.
- Inicio con saldo destacado, indicadores de ingresos/gastos, acciones rápidas, gasto acumulado diario y movimientos recientes.
- Navegación con acceso directo al asistente y herramientas desde la cabecera.
- Reportes con selección de 3, 6 y 12 meses, modos de líneas/barras, dona de categorías, escala legible y comercios principales.
- Consultas diarias y por comercio de solo lectura, sin el límite de visualización del historial.
- Asistente conversacional local, consultas por mes, comparación, presupuestos, pagos y mayor compra; entrada fija con soporte del teclado.
- Simulación de ahorro que no escribe registros financieros.
- Rediseño de cuentas, presupuestos, pagos, filtros y filas del historial. Los saldos desconocidos pueden dejarse pendientes al crear una cuenta.
- Formularios desplazables y conservación de la privacidad en indicadores, gráficas y respuestas.
- Mismo identificador, esquema SQLite y respaldo; `versionCode` pasa a 7.

## 0.4.0

- Inicio con balance confirmado del mes y acceso directo a pendientes.
- Gráfica de seis meses consecutivos, escala en quetzales, detalle numérico y selección por mes.
- Meses sin registros indicados explícitamente y gastos netos negativos representados bajo cero.
- Distribución por categoría con porcentajes calculados sobre los gastos netos positivos y acceso a sus movimientos.
- Comparación mensual con porcentaje cuando existe una base positiva y aviso para meses en curso.
- Historial agrupado por fecha, búsqueda sin distinción de tildes y filtros para pendientes, gastos, ingresos y traslados.
- Modo persistente para ocultar importes y gráficas de reportes.
- Mejoras de legibilidad, zonas táctiles y navegación; conservación de sección y filtros al recrear la actividad.
- Correcciones de compatibilidad detectadas por Android lint; Gradle 8.9 para Android Gradle Plugin 8.7.3.
- Ejemplos bancarios sustituidos por datos inventados y exclusiones para publicación pública.
- Identificador, base de datos, esquema y formato de respaldo conservados; `versionCode` aumenta a 6.
