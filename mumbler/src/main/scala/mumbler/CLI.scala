package mumbler

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import mumbler.transport.Messages._
import java.net.URI
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.actor.ActorRef
import scala.collection.mutable.ListBuffer
import akka.actor.ActorSelection
import java.util.concurrent.TimeUnit
import akka.util.Timeout

/**
 * @author mdye
 */
object CLI extends App {
  val system = ActorSystem("Mumbler")
  val conductor = system.actorOf(Props[Conductor], name = "Conductor")

  conductor ! Control(Preprocess, args)
}

class Conductor extends Actor with ActorLogging {

  def remote(host: String, port: Int) = {
    val duration = Duration.create(30, TimeUnit.SECONDS)
    implicit val timeout = Timeout(duration)
    Await.result(context.actorSelection(s"akka.tcp://RemoteMumbler@${host}:${port}/user/Agent").resolveOne(), duration)
  }

  val mumbler = new Mumbler(log, self, remote("gpfs1", 5441), remote("gpfs2", 5442), remote("gpfs3", 5443))

  var chain = ListBuffer[String]()
  var max = 10

  def receive = {
    case Control(Preprocess, args) =>
      log.info(s"Received preprocess with args: ${args.mkString(" ")}")

      max = args(1).toInt
      mumbler.distribute((0 to 99).map(ix => Download(new URI(s"http://storage.googleapis.com/books/ngrams/books/googlebooks-eng-us-all-2gram-20090715-${ix}.csv.zip"))))

      // done preprocessing, now do the mumble
      chain += args(0)
      self ! Control(Mumble, args)

    case Control(Mumble, args) => mumbler.all(Request(Mumble, args(0)))

    case _: Control => log.info(s"Unexpected command")

    case response: Response =>
      log.debug(s"Received response from remote mumbler: ${response.result.mkString(" ")}")

      // update response
      // evaluate nodes' responses
      mumbler.mumble(sender, response) match {
        case AddToChain(word) =>
          chain += word
          log.info(s"$word")
          log.debug(s"chain so far: ${chain.mkString(" ")}")

          if (chain.length == max) exit(s"reached requested max chain length, $max")
          else mumbler.all(Request(Mumble, chain.last))

        case EndChain => exit("no following words found")
        case NotAllNodesReported =>  // continue
      }


    case msg: String => log.info(s"Received message: '$msg' from $sender")
  }

  def exit(reason: String) {
    log.info(s"Exiting b/c ${reason}")
    log.info(s"Chain: ${chain.mkString(" ")}")
    context.system.shutdown()
    System.exit(1)
  }
}

object Selection {
  def randomized(following: Iterable[Int], sum: Int) = {}
}