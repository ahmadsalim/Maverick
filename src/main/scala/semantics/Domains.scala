package semantics

import syntax.{Module => _, _}

case class Module( globalVars: Map[VarName, Type]
                 , funs: Map[VarName, (Type, List[Parameter], Expr)]
                 , datatypes: Map[TypeName, List[ConsName]]
                 , constructors: Map[ConsName, (TypeName, List[Parameter])]
                 )

sealed trait Value
case class BasicValue(b: Basic) extends Value
case class ConstructorValue(name: ConsName, vals: Seq[Value]) extends Value
case class ListValue(vals: List[Value]) extends Value
case class SetValue(vals: Set[Value]) extends Value
case class MapValue(vals: Map[Value, Value]) extends Value
case object BottomValue extends Value

case class Store(map: Map[VarName, Value])

sealed trait Result[+T]
case class SuccessResult[T](t: T) extends Result[T]
case class ExceptionalResult[T](exres: Exceptional) extends Result[T]

sealed trait Exceptional
case class Return(value: Value) extends Exceptional
case class Throw(value: Value) extends Exceptional
case object Break extends Exceptional
case object Continue extends Exceptional
case object Fail extends Exceptional
case object Error extends Exceptional

object Domains {
  val prelude = Module(Map.empty, Map.empty,
    Map("Bool" -> List("true", "false"), "NoKey" -> List("nokey"), "Pair" -> List("pair")),
    Map("true" -> ("Bool", List()),
        "false" -> ("Bool", List()),
        "nokey" -> ("NoKey", List(Parameter(ValueType, "key"))),
        "pair" -> ("Pair", List(Parameter(ValueType, "fst"), Parameter(ValueType, "snd")))))
}