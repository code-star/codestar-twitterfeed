package controllers

import javax.inject.{Inject, Singleton}

import play.api.mvc._
import services.TwitterGateway

import scala.concurrent.ExecutionContext

@Singleton
class TwitterController @Inject()(gateway: TwitterGateway, cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc) {
  def twitter = Action.async {
    for {
      res <- gateway.tweets()
    } yield Ok(res.json)
  }
}
