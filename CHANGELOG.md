# Cambios

## 0.5.1

- Planificador de ahorro hipotético en la conversación: saldo inicial explícito, aporte mensual o total, plazo de 1 a 120 meses, extras, retiros y meta opcional.
- Seguimiento de escenarios al cambiar meses, aportes o retiros; solicitudes de aclaración ante datos incompletos o ambiguos.
- Formulario desplazable para planear ahorros, con selección explícita de una cuenta como punto de partida y primer aporte este mes o el próximo.
- Resultado exacto en centavos, fórmula, trayectoria y desglose mes a mes, sin modificar cuentas.
- El aporte total conserva todos sus centavos y ajusta el remanente en el último mes. Los extras y retiros únicos se aplican una sola vez en el primero.
- Cálculo del aporte mensual uniforme para una meta, identificación de faltantes y validación de montos y plazos.
- Privacidad en resultados y gráficas; conservación del contexto aun al superar los ocho mensajes visibles.
- `versionCode` 8; esquema y formato de respaldo conservados.

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
