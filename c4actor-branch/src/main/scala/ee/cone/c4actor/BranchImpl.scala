
package ee.cone.c4actor

import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble._
import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4actor.BranchTypes._
import okio.ByteString

import Function.chain

case object SessionKeysKey extends WorldKey[Set[BranchRel]](Set.empty)

case object AckChangesKey extends WorldKey[Map[String,String]](Map.empty)

case class BranchTaskImpl(branchKey: String, seeds: Values[BranchRel], product: Product) extends BranchTask {
  def sending: World ⇒ (Send,Send,World⇒World) = local ⇒ {
    val newSessionKeys = sessionKeys(local)
    val(keepTo,freshTo) = newSessionKeys.partition(SessionKeysKey.of(local))
    val send = SendToAlienKey.of(local)
    def sendingPart(to: Set[BranchRel]): Send =
      if(to.isEmpty) None
      else Some((eventName,data) ⇒ send(to.map(_.parentSrcId).toSeq, eventName, data))
    val ackAll =
      chain(AckChangesKey.of(local).map{ case(sessionKey,index) ⇒
        send(Seq(sessionKey),"ackChange",s"$branchKey $index")
      }.toSeq)
      .andThen(AckChangesKey.set(Map.empty))
      .andThen(SessionKeysKey.set(newSessionKeys))
    (sendingPart(keepTo), sendingPart(freshTo), ackAll)
  }

  def sessionKeys: World ⇒ Set[BranchRel] = local ⇒ seeds.flatMap(rel⇒
    if(rel.parentIsSession) rel :: Nil
    else {
      val world = TxKey.of(local).world
      val index = By.srcId(classOf[BranchTask]).of(world)
      index.getOrElse(rel.parentSrcId,Nil).flatMap(_.sessionKeys(local))
    }
  ).toSet
  def relocate(to: String): World ⇒ World = local ⇒ {
    val(toSessions, toNested) = seeds.partition(_.parentIsSession)
    val sessionKeys = toSessions.map(_.parentSrcId)
    val send = SendToAlienKey.of(local)(sessionKeys,"relocateHash",to)
    val nest = toNested.map((rel:BranchRel) ⇒ relocateSeed(rel.parentSrcId,rel.seed.position,to))
    send.andThen(chain(nest))(local)
  }

  def relocateSeed(branchKey: String, position: String, to: String): World ⇒ World = {
    println(s"relocateSeed: [$branchKey] [$position] [$to]")
    identity
  } //todo emulate post to branch?
}

case object ReportAliveBranchesKey extends WorldKey[String]("")

case object EmptyBranchMessage extends BranchMessage {
  override def header: String ⇒ String = _⇒""
  override def body: ByteString = ByteString.EMPTY
}

case class BranchTxTransform(
  branchKey: String,
  seed: Option[BranchResult],
  sessionKeys: List[SrcId],
  posts: List[MessageFromAlien],
  handler: BranchHandler
) extends TxTransform {
  private def saveResult: World ⇒ World = local ⇒ {
    //if(seed.isEmpty && newChildren.nonEmpty) println(s"newChildren: ${handler}")
    //println(s"BranchResult $wasBranchResults == $newBranchResult == ${wasBranchResults == newBranchResult}")
    val world = TxKey.of(local).world
    val index = By.srcId(classOf[BranchResult]).of(world)
    val wasBranchResults = index.getOrElse(branchKey,Nil)
    val wasChildren = wasBranchResults.flatMap(_.children)
    val newChildren = handler.seeds(local)
    if(wasChildren == newChildren) local
    else {
      val newBranchResult = if(newChildren.isEmpty) Nil else List(seed.get.copy(children = newChildren))
      add(wasBranchResults.flatMap(delete) ::: newBranchResult.flatMap(update))(local)
    }
  }

  private def reportAliveBranches: World ⇒ World = local ⇒ {
    val wasReport = ReportAliveBranchesKey.of(local)
    if(sessionKeys.isEmpty){
      if(wasReport.isEmpty) local else ReportAliveBranchesKey.set("")(local)
    }
    else {
      val world = TxKey.of(local).world
      val index = By.srcId(classOf[BranchResult]).of(world)
      def gather(branchKey: SrcId): List[String] = {
        val children = index.getOrElse(branchKey,Nil).flatMap(_.children).map(_.hash)
        (branchKey :: children).mkString(",") :: children.flatMap(gather)
      }
      val newReport = gather(branchKey).mkString(";")
      if(newReport == wasReport) local
      else {
        val sendToAll = SendToAlienKey.of(local)(sessionKeys,"branches",newReport)
        ReportAliveBranchesKey.set(newReport).andThen(sendToAll)(local)
      }
    }
  }

  private def getPosts: Seq[BranchMessage] =
    if(posts.isEmpty) Seq(EmptyBranchMessage) else posts

  def transform(local: World): World = {
    if(ErrorKey.of(local).nonEmpty) chain(posts.map(_.rm))(local)
    else chain(getPosts.map(post⇒toAck(post).andThen(handler.exchange(post))))
      .andThen(saveResult).andThen(reportAliveBranches)
      .andThen(chain(posts.map(_.rm)))(local)
  }

  private def toAck: BranchMessage ⇒ World ⇒ World = exchange ⇒ {
    val sessionKey = exchange.header("X-r-session")
    if(sessionKey.isEmpty) identity[World]
    else AckChangesKey.modify(_ + (sessionKey → exchange.header("X-r-index")))
  }
}

class BranchOperationsImpl(registry: QAdapterRegistry) extends BranchOperations {
  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
  def toSeed(value: Product): BranchResult = {
    val valueAdapter = registry.byName(value.getClass.getName)
    val bytes = valueAdapter.encode(value)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    val id = UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes)
    BranchResult(id.toString, valueAdapter.id, byteString, Nil, "")
  }
  def toRel(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel) =
    seed.hash → BranchRel(s"${seed.hash}/$parentSrcId",seed,parentSrcId,parentIsSession)
}

@assemble class BranchAssemble(registry: QAdapterRegistry, operations: BranchOperations) extends Assemble {
  def mapBranchSeedsByChild(
    key: SrcId,
    branchResults: Values[BranchResult]
  ): Values[(BranchKey,BranchRel)] =
    for(branchResult ← branchResults; child ← branchResult.children)
      yield operations.toRel(child, branchResult.hash, parentIsSession=false)

  def joinBranchTask(
      key: SrcId,
      wasBranchResults: Values[BranchResult],
      @by[BranchKey] seeds: Values[BranchRel]
  ): Values[(SrcId,BranchTask)] = {
    val seed = seeds.headOption.map(_.seed).getOrElse(Single(wasBranchResults))
    registry.byId.get(seed.valueTypeId).map(_.decode(seed.value.toByteArray))
      .map(product => key → BranchTaskImpl(key, seeds, product)).toList
    // may result in some garbage branches in the world?

    //println(s"join_task $key ${wasBranchResults.size} ${seeds.size}")
  }


  def joinTxTransform(
    key: SrcId,
    @by[BranchKey] seeds: Values[BranchRel],
    @by[BranchKey] posts: Values[MessageFromAlien],
    handlers: Values[BranchHandler]
  ): Values[(SrcId,TxTransform)] =
    for(handler ← Single.option(handlers).toList)
      yield key → BranchTxTransform(key,
        seeds.headOption.map(_.seed),
        seeds.filter(_.parentIsSession).map(_.parentSrcId),
        posts.sortBy(_.index),
        handler
      )
}

//todo relocate toAlien
//todo error in view
//todo checkUpdate()?

/*
@assemble class PurgeBranchAssemble extends Assemble {
  def purgeBranches(
    key: SrcId,
    tasks: Values[BranchTaskSender]
  ): Values[(SrcId,BranchHandler)] =
    for(task ← tasks if task.product == None) yield key → VoidBranchHandler()
}*/

/*
case class ProductProtoAdapter(className: SrcId, id: Long)(val adapter: ProtoAdapter[Product])
def createWorld: World ⇒ World = setupAssembler andThen setupAdapters
private def setupAdapters =
    By.srcId(classOf[ProductProtoAdapter]).set(protocols.flatMap(_.adapters).map(adapter⇒
      adapter.className →
        ProductProtoAdapter(adapter.id,adapter.className)(adapter.asInstanceOf[ProtoAdapter[Product]]) :: Nil
    ).toMap)


@assemble class AdapterAssemble extends Assemble {
  type AnnotationId = Long
  def joinProductProtoAdapterByAnnotationId(
    key: SrcId,
    adapters: Values[ProductProtoAdapter]
  ): Values[(AnnotationId,ProductProtoAdapter)] =
    adapters.map(a⇒a.id→a)
}

*/

// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])

/* embed plan:
TcpWrite to many conns
dispatch to service by sse.js
posts to connections and sseUI-s
vdom emb host/guest
subscr? cli or serv
RootViewResult(...,subviews)
/
@ FromAlien(sessionKey,locationHash)
/
@@ Embed(headers)
@ UIResult(srcId {connectionKey|embedHash}, needChildEmbeds, connectionKeys)
/
TxTr(embedHash,embed,connections)

next:
"X-r-vdom-branch"

?errors in embed
?bind/positioning: ref=['embed','parent',key]
*/