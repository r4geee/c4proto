package ee.cone.c4vdom_impl

import ee.cone.c4vdom._

import Function.chain

class VDomHandlerFactoryImpl(
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory
) extends VDomHandlerFactory {
  def create[State](
    sender: VDomSender[State],
    view: VDomView[State],
    vDomUntil: VDomUntil,
    vDomStateKey: VDomLens[State,Option[VDomState]]
  ): VDomHandler[State] =
    VDomHandlerImpl(sender,view)(diff,jsonToString,wasNoValue,child,vDomUntil,vDomStateKey)
}

case class VDomHandlerImpl[State](
  sender: VDomSender[State],
  view: VDomView[State]
)(
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  vDomUntil: VDomUntil,

  vDomStateKey: VDomLens[State,Option[VDomState]]
  //relocateKey: VDomLens[State,String]
) extends VDomHandler[State] {

  private def empty = Option(VDomState(wasNoValue,0))
  private def init: Handler = _ ⇒ vDomStateKey.modify(_.orElse(empty))

  //dispatches incoming message // can close / set refresh time
  private def dispatch: Handler = exchange ⇒ state ⇒ if(exchange.header("X-r-action").isEmpty) state else {
    val pathStr = exchange.header("X-r-vdom-path")

    val path = pathStr.split("/").toList match {
      case "" :: parts => parts
      case _ => Never()
    }
    ((exchange.header("X-r-action"), ResolveValue(vDomStateKey.of(state).get.value, path)) match {
      case ("click", Some(v: OnClickReceiver[_])) => v.onClick.get(exchange.body)
      case ("change", Some(v: OnChangeReceiver[_])) => v.onChange.get(exchange.body)
      case v => throw new Exception(s"$path ($v) can not receive")
    }).asInstanceOf[State=>State](state)
  }

  //todo invalidate until by default

  def exchange: Handler = m ⇒ chain(Seq(init,dispatch,toAlien).map(_(m)))

  private def diffSend(prev: VDomValue, next: VDomValue, send: sender.Send): State ⇒ State = {
    if(send.isEmpty) return identity[State]
    val diffTree = diff.diff(prev, next)
    if(diffTree.isEmpty) return identity[State]
    val diffStr = jsonToString(diffTree.get)
    send.get("showDiff",s"${sender.branchKey} $diffStr")
  }

  private def toAlien: Handler = exchange ⇒ state ⇒ {
    val vState = vDomStateKey.of(state).get
    val (keepTo,freshTo,ackAll) = sender.sending(state)


    if(keepTo.isEmpty && freshTo.isEmpty){
      vDomStateKey.set(empty)(state) //orElse in init bug
    }
    else if(
      vState.value != wasNoValue &&
      vState.until > System.currentTimeMillis &&
      exchange.header("X-r-action").isEmpty &&
      freshTo.isEmpty
    ) state
    else {
      val (until,viewRes) = vDomUntil.get(view.view(state))
      val vPair = child("root", RootElement(sender.branchKey), viewRes).asInstanceOf[VPair]
      val nextDom = vPair.value
      vDomStateKey.set(Option(VDomState(nextDom, until)))
        .andThen(diffSend(vState.value, nextDom, keepTo))
        .andThen(diffSend(wasNoValue, nextDom, freshTo))
        .andThen(ackAll)(state)
    }
  }



  def seeds: State ⇒ List[(String,Product)] = state ⇒ {
    //println(vDomStateKey.of(state).get.value.getClass)
    gatherSeeds(Nil, Nil, vDomStateKey.of(state).get.value)
  }
  private def gatherSeeds(
    acc: List[(String,Product)], path: List[String], value: VDomValue
  ): List[(String,Product)] = value match {
    case n: MapVDomValue ⇒
      (acc /: n.pairs)((acc,pair)⇒gatherSeeds(acc, pair.jsonKey::path, pair.value))
    case SeedElement(seed) ⇒ (path.reverse.map(e⇒s"/$e").mkString,seed) :: acc
     //case UntilElement(until) ⇒ acc.copy(until = Math.min(until, acc.until))
    case _ ⇒ acc
  }

  //val relocateCommands = if(vState.hashFromAlien==vState.hashTarget) Nil
  //else List("relocateHash"→vState.hashTarget)

  /*
  private lazy val PathSplit = """(.*)(/[^/]*)""".r
  private def view(pathPrefix: String, pathPostfix: String): List[ChildPair[_]] =
    Single.option(handlerLists.list(ViewPath(pathPrefix))).map(_(pathPostfix))
      .getOrElse(pathPrefix match {
        case PathSplit(nextPrefix,nextPostfix) =>
          view(nextPrefix,s"$nextPostfix$pathPostfix")
      })
  */
}




case class RootElement(branchKey: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("ref");{
      builder.startArray()
      builder.append("root")
      builder.append(branchKey)
      builder.end()
    }
    builder.end()
  }
}

object ResolveValue {
  def apply(value: VDomValue, path: List[String]): Option[VDomValue] =
    if(path.isEmpty) Some(value) else Some(value).collect{
      case m: MapVDomValue => m.pairs.collectFirst{
        case pair if pair.jsonKey == path.head => apply(pair.value, path.tail)
      }.flatten
    }.flatten
}
