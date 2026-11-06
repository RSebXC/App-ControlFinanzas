package gt.finanzas.privadas

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

@android.annotation.SuppressLint("ViewConstructor")
class FinanceTrendView(
    context: Context,
    private val reports: List<FinanceDb.MonthReport>,
    private val selected: String,
    private val lineMode: Boolean = false,
    private val onMonthSelected: (String) -> Unit
) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val fill=Path()
    private val density=resources.displayMetrics.density
    private fun px(v:Float)=v*density
    private var downX=0f
    private var downY=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val labels=reports.map { YearMonth.parse(it.ym).format(DateTimeFormatter.ofPattern(if(reports.size>6) "MMM" else "MMM yy",Locale("es","GT"))) }
    init {
        isClickable=true;isFocusable=true
        contentDescription="Ingresos y gastos netos en quetzales. " + reports.joinToString(". ") {
            "${it.ym}: ingresos ${FinanceDb.format(it.income)}, gastos netos ${FinanceDb.format(it.expenses)}" + if(it.count==0L) ", sin movimientos registrados" else ""
        }
    }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        if(reports.isEmpty())return
        val left=px(43f);val right=width-px(8f);val top=px(15f);val bottom=height-px(32f)
        val maxValue=reports.maxOf { maxOf(it.income,it.expenses,100L) }.toDouble()
        val minValue=reports.minOf { minOf(it.expenses,0L) }.toDouble()
        val rawStep=(maxValue-minValue)/4
        val magnitude=10.0.pow(floor(log10(rawStep.coerceAtLeast(1.0))))
        val normalized=rawStep/magnitude
        val step=(when { normalized<=1 -> 1;normalized<=2 -> 2;normalized<=5 -> 5;else -> 10 })*magnitude
        val high=ceil(maxValue/step)*step
        val low=floor(minValue/step)*step
        fun y(value:Double)=(top+(high-value)/(high-low)*(bottom-top)).toFloat()
        val zero=y(0.0)
        paint.textSize=px(10f);paint.strokeWidth=px(1f);paint.style=Paint.Style.FILL
        val ticks=((high-low)/step).toInt().coerceAtMost(10)
        for(i in 0..ticks) {
            val value=low+i*step;val y=y(value)
            paint.color=if(value==0.0) Color.rgb(184,201,203) else FinanceDesign.line
            c.drawLine(left,y,right,y,paint)
            paint.color=FinanceDesign.muted;paint.textAlign=Paint.Align.RIGHT
            c.drawText(ReportMath.compact(value.toLong()),left-px(7f),y+px(3f),paint)
        }
        val group=(right-left)/reports.size
        fun x(index:Int)=left+group*(index+.5f)
        if(lineMode) {
            fun series(color:Int, income:Boolean) {
                paint.color=color;paint.strokeWidth=px(2.5f);paint.strokeCap=Paint.Cap.ROUND
                var start=-1
                fun flush(end:Int) {
                    if(start<0)return
                    fill.set(path);fill.lineTo(x(end),zero);fill.lineTo(x(start),zero);fill.close()
                    paint.style=Paint.Style.FILL;paint.color=(color and 0x00ffffff) or 0x10000000
                    c.drawPath(fill,paint)
                    paint.color=color;paint.style=Paint.Style.STROKE;c.drawPath(path,paint);paint.style=Paint.Style.FILL
                    start=-1;path.reset()
                }
                reports.forEachIndexed { i,r ->
                    if(r.count==0L)flush(i-1)
                    else {
                        val py=y((if(income)r.income else r.expenses).toDouble())
                        if(start<0){start=i;path.moveTo(x(i),py)}else path.lineTo(x(i),py)
                        paint.style=Paint.Style.FILL;paint.color=color;c.drawCircle(x(i),py,px(3f),paint)
                    }
                }
                flush(reports.lastIndex)
            }
            series(FinanceDesign.teal,true);series(FinanceDesign.coral,false)
        } else {
            val barWidth=minOf(group*.25f,px(14f))
            reports.forEachIndexed { i,r ->
                fun bar(value:Long,x:Float,color:Int) {
                    if(value==0L)return
                    val end=y(value.toDouble());paint.color=color
                    c.drawRoundRect(x,minOf(zero,end),x+barWidth,maxOf(zero,end),px(3f),px(3f),paint)
                }
                bar(r.income,x(i)-barWidth-px(2f),FinanceDesign.teal)
                bar(r.expenses,x(i)+px(2f),FinanceDesign.coral)
            }
        }
        reports.forEachIndexed { i,r ->
            paint.style=Paint.Style.FILL;paint.color=if(r.ym==selected)FinanceDesign.teal else FinanceDesign.muted
            paint.textAlign=Paint.Align.CENTER;paint.isFakeBoldText=r.ym==selected
            if(reports.size<=6 || i%2==0 || i==reports.lastIndex)c.drawText(labels[i],x(i),height-px(10f),paint)
            paint.isFakeBoldText=false
            if(r.count==0L)c.drawText("—",x(i),zero-px(7f),paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {downX=event.x;downY=event.y}
            MotionEvent.ACTION_UP -> if(abs(event.x-downX)<slop && abs(event.y-downY)<slop) {
                if(event.x>=px(43f) && event.x<=width-px(8f) && reports.isNotEmpty()) {
                    val index=((event.x-px(43f))/((width-px(51f))/reports.size)).toInt().coerceIn(reports.indices)
                    onMonthSelected(reports[index].ym)
                };performClick()
            }
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}

@android.annotation.SuppressLint("ViewConstructor")
class CategoryDonutView(context:Context, private val categories:List<Pair<String,Long>>) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private val total=categories.sumOf { it.second.coerceAtLeast(0) }
    init {
        contentDescription="Distribución de categorías con gasto neto positivo: " + categories.filter{it.second>0}.joinToString { "${it.first}, ${ReportMath.share(it.second,total)} por ciento" }
    }
    override fun onDraw(c:Canvas) {
        val d=resources.displayMetrics.density
        val radius=minOf(width,height)*.5f-12*d
        val cx=width/2f;val cy=height/2f;val stroke=19*d
        rect.set(cx-radius+stroke/2,cy-radius+stroke/2,cx+radius-stroke/2,cy+radius-stroke/2)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.strokeCap=Paint.Cap.BUTT;paint.color=FinanceDesign.line
        c.drawOval(rect,paint)
        var angle=-90f
        categories.forEachIndexed { index,(_,amount) ->
            if(amount>0 && total>0) {
                val sweep=(amount.toDouble()/total*360).toFloat()
                paint.color=FinanceDesign.categoryColors[index%FinanceDesign.categoryColors.size]
                c.drawArc(rect,angle+1f,(sweep-2f).coerceAtLeast(.1f),false,paint);angle+=sweep
            }
        }
        paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.color=FinanceDesign.ink;paint.textSize=21*d;paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
        c.drawText("Q${ReportMath.compact(total)}",cx,cy+2*d,paint)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL);paint.textSize=10*d;paint.color=FinanceDesign.muted
        c.drawText("DISTRIBUIDO",cx,cy+21*d,paint)
    }
}

@android.annotation.SuppressLint("ViewConstructor")
class ExpensePulseView(context:Context, daily:List<Long>) : View(context) {
    private val values=FinanceAnalysis.cumulative(daily)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val fill=Path()
    init {
        contentDescription="Gasto neto acumulado por día. " + values.mapIndexed { i,v -> "Día ${i+1}: ${FinanceDb.format(v)}" }.joinToString(". ")
    }
    override fun onDraw(c:Canvas) {
        if(values.isEmpty())return
        val d=resources.displayMetrics.density
        val left=43*d;val right=width-12*d;val top=12*d;val bottom=height-29*d
        val high=(values.maxOrNull()?:0).coerceAtLeast(100).toDouble()
        val low=(values.minOrNull()?:0).coerceAtMost(0).toDouble()
        fun y(v:Double)=(top+(high-v)/(high-low)*(bottom-top)).toFloat()
        fun x(i:Int)=left+(right-left)*i/values.lastIndex.coerceAtLeast(1)
        paint.strokeWidth=d;paint.textSize=10*d;paint.textAlign=Paint.Align.RIGHT
        for(step in 0..2) {
            val v=low+(high-low)*step/2;val py=y(v)
            paint.color=FinanceDesign.line;c.drawLine(left,py,right,py,paint)
            paint.color=FinanceDesign.muted;c.drawText(ReportMath.compact(v.toLong()),left-7*d,py+3*d,paint)
        }
        path.reset();path.moveTo(x(0),y(values[0].toDouble()))
        values.forEachIndexed {i,v -> if(i>0)path.lineTo(x(i),y(v.toDouble()))}
        fill.set(path);fill.lineTo(x(values.lastIndex),y(0.0));fill.lineTo(x(0),y(0.0));fill.close()
        paint.shader=LinearGradient(0f,top,0f,bottom,intArrayOf(0x38007e77,0x00007e77),null,Shader.TileMode.CLAMP)
        c.drawPath(fill,paint);paint.shader=null
        paint.color=FinanceDesign.teal;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f*d;c.drawPath(path,paint);paint.style=Paint.Style.FILL
        c.drawCircle(x(values.lastIndex),y(values.last().toDouble()),4*d,paint)
        paint.color=FinanceDesign.muted;paint.textAlign=Paint.Align.LEFT;paint.textSize=10*d;c.drawText("Día 1",left,height-8*d,paint)
        paint.textAlign=Paint.Align.RIGHT;c.drawText("Día ${values.size}",right,height-8*d,paint)
    }
}
