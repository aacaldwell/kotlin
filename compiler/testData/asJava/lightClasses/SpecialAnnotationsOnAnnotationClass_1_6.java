@java.lang.annotation.Documented()
@java.lang.annotation.Repeatable(value = Anno.Container.class)
@java.lang.annotation.Retention(value = java.lang.annotation.RetentionPolicy.SOURCE)
@java.lang.annotation.Target(value = {})
@kotlin.annotation.MustBeDocumented()
@kotlin.annotation.Repeatable()
@kotlin.annotation.Retention(value = kotlin.annotation.AnnotationRetention.SOURCE)
@kotlin.annotation.Target(allowedTargets = {kotlin.annotation.AnnotationTarget.TYPE_PARAMETER, kotlin.annotation.AnnotationTarget.TYPE})
public abstract @interface Anno /* Anno*/ {
  public abstract int i();//  i()


@java.lang.annotation.Retention(value = java.lang.annotation.RetentionPolicy.SOURCE)
@java.lang.annotation.Target(value = {})
@kotlin.annotation.Retention(value = kotlin.annotation.AnnotationRetention.SOURCE)
@kotlin.annotation.Target(allowedTargets = {kotlin.annotation.AnnotationTarget.TYPE_PARAMETER, kotlin.annotation.AnnotationTarget.TYPE})
@kotlin.jvm.internal.RepeatableContainer()
public static abstract @interface Container /* Anno.Container*/ {
  public abstract Anno[] value();//  value()

}}