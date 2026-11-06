package gt.finanzas.privadas

import android.graphics.*
import android.graphics.drawable.Drawable

/** Shared visual language. Icons are vectors, with no fonts or network dependencies. */
object FinanceDesign {
    val background = Color.rgb(244, 246, 250)
    val ink = Color.rgb(22, 43, 54)
    val muted = Color.rgb(101, 119, 132)
    val teal = Color.rgb(0, 126, 119)
    val navy = Color.rgb(16, 47, 56)
    val mint = Color.rgb(229, 247, 242)
    val coral = Color.rgb(208, 80, 98)
    val purple = Color.rgb(114, 88, 207)
    val line = Color.rgb(231, 236, 242)
    val categoryColors = listOf(teal, Color.rgb(111, 104, 213), Color.rgb(236, 160, 77), Color.rgb(84, 152, 211), coral, Color.rgb(96, 163, 130))
    fun categoryIcon(category: String) = when(category) {
        "Comida" -> "food"
        "Gasolina", "Transporte" -> "fuel"
        "Servicios" -> "bolt"
        "Suscripciones", "Entretenimiento" -> "play"
        "Compras" -> "bag"
        "Hogar" -> "home"
        "Salud" -> "heart"
        "Educación" -> "book"
        "Por revisar" -> "clock"
        else -> "grid"
    }
}

class FinanceIcon(private val name: String, private val tint: Int, size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=tint;style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private val path = Path()
    init { setBounds(0,0,size,size) }
    override fun getIntrinsicWidth() = bounds.width()
    override fun getIntrinsicHeight() = bounds.height()
    override fun draw(c: Canvas) {
        val save=c.save()
        c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(x:Float,y:Float,x2:Float,y2:Float)=c.drawLine(x,y,x2,y2,paint)
        fun circle(x:Float,y:Float,r:Float)=c.drawCircle(x,y,r,paint)
        fun rect(l:Float,t:Float,r:Float,b:Float,rad:Float=2f)=c.drawRoundRect(l,t,r,b,rad,rad,paint)
        fun points(vararg values:Float) {
            path.reset();path.moveTo(values[0],values[1]);var i=2
            while(i<values.size){path.lineTo(values[i],values[i+1]);i+=2};c.drawPath(path,paint)
        }
        when(name) {
            "home" -> {points(3f,10f,12f,3f,21f,10f);points(5f,9f,5f,21f,19f,21f,19f,9f);points(9f,21f,9f,14f,15f,14f,15f,21f)}
            "chart" -> {points(4f,4f,4f,20f,21f,20f);points(7f,14f,11f,10f,15f,12f,21f,5f);points(17f,5f,21f,5f,21f,9f)}
            "bars" -> {line(5f,20f,5f,12f);line(12f,20f,12f,5f);line(19f,20f,19f,9f)}
            "wallet" -> {rect(3f,6f,21f,20f,3f);points(4f,7f,4f,4f,17f,3f,17f,6f);rect(15f,11f,21f,16f,1f);circle(17.5f,13.5f,.4f)}
            "activity", "transfer" -> {points(4f,7f,20f,7f,16f,3f);points(20f,17f,4f,17f,8f,21f)}
            "spark" -> {points(12f,3f,14.5f,9.5f,21f,12f,14.5f,14.5f,12f,21f,9.5f,14.5f,3f,12f,9.5f,9.5f,12f,3f);line(20f,2f,20f,6f);line(18f,4f,22f,4f)}
            "add" -> {line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
            "refresh" -> {c.drawArc(4f,4f,20f,20f,40f,280f,false,paint);points(15f,4f,20f,4f,20f,9f)}
            "eye", "eyeoff" -> {path.reset();path.moveTo(2f,12f);path.cubicTo(7f,3f,17f,3f,22f,12f);path.cubicTo(17f,21f,7f,21f,2f,12f);c.drawPath(path,paint);circle(12f,12f,3f);if(name=="eyeoff")line(3f,3f,21f,21f)}
            "settings" -> {line(3f,6f,21f,6f);line(3f,12f,21f,12f);line(3f,18f,21f,18f);circle(8f,6f,2f);circle(16f,12f,2f);circle(10f,18f,2f)}
            "back" -> {points(14f,5f,7f,12f,14f,19f)}
            "next" -> {points(9f,5f,16f,12f,9f,19f)}
            "up", "down" -> {if(name=="up"){points(6f,10f,12f,4f,18f,10f);line(12f,4f,12f,20f)}else{points(6f,14f,12f,20f,18f,14f);line(12f,4f,12f,20f)}}
            "search" -> {circle(10.5f,10.5f,6.5f);line(16f,16f,21f,21f)}
            "calendar" -> {rect(4f,5f,20f,21f);line(8f,3f,8f,7f);line(16f,3f,16f,7f);line(4f,10f,20f,10f);line(8f,14f,11f,14f);line(14f,14f,16f,14f)}
            "budget" -> {circle(12f,12f,9f);circle(12f,12f,5f);circle(12f,12f,1f)}
            "shield" -> {points(12f,3f,20f,6f,19f,15f,16f,19f,12f,22f,8f,19f,5f,15f,4f,6f,12f,3f);points(8f,12f,11f,15f,16f,9f)}
            "download" -> {points(7f,10f,12f,15f,17f,10f);line(12f,3f,12f,15f);points(4f,16f,4f,21f,20f,21f,20f,16f)}
            "send" -> {points(3f,3f,22f,12f,3f,21f,6f,12f,3f,3f);line(6f,12f,22f,12f)}
            "food" -> {line(5f,3f,5f,9f);line(8f,3f,8f,21f);line(11f,3f,11f,9f);c.drawArc(5f,6f,11f,12f,0f,180f,false,paint);points(19f,3f,16f,10f,19f,10f,19f,21f)}
            "fuel" -> {rect(4f,4f,14f,20f);rect(6f,6f,12f,11f,1f);line(3f,21f,15f,21f);points(14f,12f,17f,12f,17f,18f,20f,18f,20f,7f,17f,4f)}
            "bolt" -> {points(13f,2f,5f,14f,11f,14f,10f,22f,20f,9f,13f,9f,13f,2f)}
            "play" -> {rect(3f,5f,21f,19f,4f);points(10f,9f,16f,12f,10f,15f,10f,9f)}
            "bag" -> {rect(4f,8f,20f,21f);c.drawArc(8f,2f,16f,13f,180f,180f,false,paint)}
            "heart" -> {path.reset();path.moveTo(12f,21f);path.cubicTo(-7f,10f,5f,-3f,12f,7f);path.cubicTo(19f,-3f,31f,10f,12f,21f);c.drawPath(path,paint)}
            "book" -> {points(12f,21f,12f,5f,4f,3f,4f,19f,12f,21f,20f,19f,20f,3f,12f,5f)}
            "clock" -> {circle(12f,12f,9f);points(12f,7f,12f,12f,16f,14f)}
            "check" -> {points(5f,12f,10f,17f,20f,6f)}
            else -> {rect(4f,4f,10f,10f,1.5f);rect(14f,4f,20f,10f,1.5f);rect(4f,14f,10f,20f,1.5f);rect(14f,14f,20f,20f,1.5f)}
        }
        c.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
