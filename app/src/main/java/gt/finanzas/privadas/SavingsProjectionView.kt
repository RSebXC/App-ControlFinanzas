package gt.finanzas.privadas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

@android.annotation.SuppressLint("ViewConstructor")
class SavingsProjectionView(context:Context,projection:SavingsPlanner.Projection):View(context) {
    private val values=listOf(requireNotNull(projection.plan.initial))+projection.rows.map{it.closing}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    init {
        contentDescription="Escenario de ahorro sin intereses. Saldo inicial ${FinanceDb.format(values.first())}. "+projection.rows.joinToString(". "){"Mes ${it.number}: ${FinanceDb.format(it.closing)}"}
    }
    override fun onDraw(c:Canvas) {
        val d=resources.displayMetrics.density
        val left=48*d;val right=width-12*d;val top=14*d;val bottom=height-28*d
        val high=values.maxOrNull()!!.coerceAtLeast(100).toDouble()
        val low=values.minOrNull()!!.coerceAtMost(0).toDouble()
        fun x(index:Int)=left+(right-left)*index/values.lastIndex
        fun y(value:Long)=(top+(high-value)/(high-low)*(bottom-top)).toFloat()
        paint.textSize=10*d;paint.textAlign=Paint.Align.RIGHT;paint.strokeWidth=d
        for(i in 0..2) {
            val amount=(low+(high-low)*i/2).toLong();val py=y(amount)
            paint.color=FinanceDesign.line;c.drawLine(left,py,right,py,paint)
            paint.color=FinanceDesign.muted;c.drawText(ReportMath.compact(amount),left-8*d,py+3*d,paint)
        }
        path.reset();values.forEachIndexed{i,v->if(i==0)path.moveTo(x(i),y(v))else path.lineTo(x(i),y(v))}
        paint.color=FinanceDesign.purple;paint.strokeWidth=3*d;paint.style=Paint.Style.STROKE;c.drawPath(path,paint);paint.style=Paint.Style.FILL
        c.drawCircle(x(0),y(values.first()),3*d,paint);c.drawCircle(x(values.lastIndex),y(values.last()),4*d,paint)
        paint.color=FinanceDesign.muted;paint.textAlign=Paint.Align.LEFT;c.drawText("Inicial",left,height-8*d,paint)
        paint.textAlign=Paint.Align.RIGHT;c.drawText("Mes ${values.lastIndex}",right,height-8*d,paint)
    }
}
