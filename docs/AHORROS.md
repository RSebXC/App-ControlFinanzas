# Planear ahorros con el asistente

Las proyecciones son escenarios aritméticos locales, no una predicción bancaria. No crean movimientos, no reservan fondos y no modifican saldos. No usan intereses, rendimientos, inflación ni conversiones de moneda.

## Conversación

Con datos ficticios:

1. «Si ahorro Q500 al mes durante 2 meses y ya tengo Q1,000» → Q2,000.
2. «¿Y si son 3 meses?» → Q2,500, conservando los otros supuestos.
3. «Y si retiro Q200» → Q2,300, considerando un retiro único.
4. «Mejor Q700 al mes» → Q2,900, manteniendo los tres meses y el retiro.

También admite `aporte extra Q200`, `retiro mensual Q50` y `meta Q5000`. Escribe «Nuevo escenario» para comenzar sin heredar los supuestos anteriores. La conversación se puede limpiar desde la pantalla.

Si no indicas un ahorro inicial, te lo pregunta. Puedes responder «desde cero». Si escribes «ahorro Q500 durante 2 meses», pide aclarar «cada mes» o «en total». No suma automáticamente tus cuentas ni adivina ingresos futuros.

## Formulario

En **Asistente → Planear mis ahorros** indica:

- Ahorro inicial y cuánto apartarás cada mes.
- Plazo de 1 a 120 meses.
- Primer aporte este mes o el próximo.
- Opcionalmente, un extra único, un retiro único, un retiro mensual y una meta.

Los opcionales vacíos valen cero. **Elegir saldo de una cuenta** permite usar solo el saldo estimado de la cuenta seleccionada como punto de partida; no transfiere dinero y no consulta al banco.

## Cálculo y supuestos

`saldo final = ahorro inicial + aportes + extra único − retiro único − retiros mensuales`

- Los aportes se consideran al cierre de cada mes.
- En una conversación, el primer aporte se ubica por defecto en el próximo mes; el formulario permite elegir este mes.
- El extra y el retiro único se aplican una vez en el primer mes.
- Si defines un aporte total, se distribuye entre los meses y los centavos restantes se ajustan en el último.
- La meta muestra el faltante y el aporte mensual uniforme necesario, redondeado hacia arriba al centavo, manteniendo los otros supuestos.
- Si el escenario entra en negativo, se informa el mes del faltante; no se supone que el banco financiará ese déficit.
- Los gastos, ingresos y pagos que no indiques no se agregan a la proyección.

La respuesta muestra los supuestos y ofrece **Ver desglose mes a mes**. Las gráficas y los resultados se ocultan al activar el ojo de privacidad; los textos que escribiste siguen visibles.

El reconocimiento usa reglas para las expresiones descritas. Si una frase no se entiende, usa los nombres de los campos o el formulario; no es un modelo de IA generativa.
