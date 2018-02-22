package com.tudorzgureanu.kafka.consumers

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import cakesolutions.kafka.KafkaConsumer
import cakesolutions.kafka.akka.{ConsumerRecords, KafkaConsumerActor}
import cakesolutions.kafka.akka.KafkaConsumerActor.Confirm
import com.tudorzgureanu.kafka.RestartableActor
import com.tudorzgureanu.kafka.RestartableActor.RestartActor
import com.tudorzgureanu.domain._
import com.tudorzgureanu.protocol.users.v1.UsersEnvelope
import com.tudorzgureanu.protocol.users.v1.UsersEnvelope.Payload
import com.tudorzgureanu.services.UserService

import scala.concurrent.Future

object UserConsumerActor {

  def props(
    kafkaConsumerConf: KafkaConsumer.Conf[String, UsersEnvelope],
    kafkaConsumerActorConf: KafkaConsumerActor.Conf,
    userService: UserService
  ): Props = Props(new UserConsumerActor(kafkaConsumerConf, kafkaConsumerActorConf, userService))
}

class UserConsumerActor(
  kafkaConsumerConf: KafkaConsumer.Conf[String, UsersEnvelope],
  kafkaConsumerActorConf: KafkaConsumerActor.Conf,
  userService: UserService
) extends Actor
    with RestartableActor
    with ActorLogging {

  import context.dispatcher

  private val UserEventsExtractor = ConsumerRecords.extractor[String, UsersEnvelope]

  // provide a consumer factory as dependency for testability
  val kafkaConsumerActor = context.actorOf(KafkaConsumerActor.props(kafkaConsumerConf, kafkaConsumerActorConf, self))
  context.watch(kafkaConsumerActor)

  override def receive: Receive = {
    case UserEventsExtractor(records) =>
      Future
        .traverse(records.recordsList)(record => processUserEvent(Option(record.key()), record.value()))
        .map { _ =>
          kafkaConsumerActor ! Confirm(offsets = records.offsets, commit = true)
        }
        .recover {
          case ex =>
            log.error(ex, "Failed to process records.")
            self ! RestartActor(ex)
        }

    case Terminated(_) =>
      self ! RestartActor(new Exception("Kafka Consumer actor terminated. Restarting UserConsumerActor."))
  }

  private def processUserEvent(key: Option[String], value: UsersEnvelope): Future[Either[String, UserEvent]] = {
    value.payload match {
      case Payload.UserCreated(userCreatedProto) =>
        log.info(s"[correlationId: ${value.correlationId}] User created $userCreatedProto")
        userService
          .persistUserEvent(UserCreated.fromProto(userCreatedProto))
          .map(Right(_))
      case Payload.UserUpdated(userUpdatedProto) =>
        log.info(s"[correlationId: ${value.correlationId}] User updated $userUpdatedProto")
        userService
          .persistUserEvent(UserUpdated.fromProto(userUpdatedProto))
          .map(Right(_))
      case Payload.UserDeactivated(userDeactivatedProto) =>
        log.info(s"[correlationId: ${value.correlationId}] User deactivated $userDeactivatedProto")
        userService.persistUserEvent(UserDeactivated.fromProto(userDeactivatedProto)).map(Right(_))
      case Payload.Empty =>
        log.info(
          s"[correlationId: ${value.correlationId}] Unexpected payload with key: ${key.getOrElse("null")}. Payload ignored."
        )
        Future.successful(Left("Couldn't not process payload."))
    }
  }

}
