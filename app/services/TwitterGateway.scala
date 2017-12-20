package services

import javax.inject.Inject

import play.api.cache._
import play.api.libs.ws.WSAuthScheme.BASIC
import play.api.libs.ws._
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Future}

class TwitterGateway @Inject()(cache: AsyncCacheApi, ws: WSClient, config: Configuration)(implicit ec: ExecutionContext) {
  /*
  Source: https://developer.twitter.com/en/docs/basics/authentication/overview/application-only
  Retrieved: 20-12-2017
  
  Example request (Authorization header has been wrapped):
  
  POST /oauth2/token HTTP/1.1
  Host: api.twitter.com
  User-Agent: My Twitter App v1.0.23
  Authorization: Basic eHZ6MWV2RlM0d0VFUFRHRUZQSEJvZzpMOHFxOVBaeVJn
                       NmllS0dFS2hab2xHQzB2SldMdzhpRUo4OERSZHlPZw==
  Content-Type: application/x-www-form-urlencoded;charset=UTF-8
  Content-Length: 29
  Accept-Encoding: gzip
  
  grant_type=client_credentials
  If the request was formatted correctly, the server will respond with a JSON-encoded payload:
  
  Example response:
  
  HTTP/1.1 200 OK
  Status: 200 OK
  Content-Type: application/json; charset=utf-8
  ...
  Content-Encoding: gzip
  Content-Length: 140
  
  {"token_type":"bearer","access_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%2FAAAAAAAAAAAAAAAAAAAA%3DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
  Applications should verify that the value associated with the token_type key of the returned object is bearer. The value associated with the access_token key is the bearer token.
  
  Note that one bearer token is valid for an application at a time. Issuing another request with the same credentials to /oauth2/token will return the same token until it is invalidated.
  */

  def tweets(): Future[WSResponse] = tweets(numberOfRetries = 1)

  private def tweets(numberOfRetries: Int): Future[WSResponse] = {
    for {
      token <- cache.getOrElseUpdate[String]("bearer")(fetchNewToken)
      initialResponse <- fetchTweets(token)
      result <- retryToken(initialResponse, numberOfRetries - 1)
    } yield result
  }

  private def retryToken(response: WSResponse, retries: Int) = 
    if (isInvalidExpiredToken(response)) {
      Logger.info(s"Token invalid or expired: requesting a new token")
      for {
        _ <- cache.remove("bearer")
        tweets <- tweets(retries)
      } yield tweets
    }
    else Future.successful(response)

  private def fetchTweets(bearerToken: String): Future[WSResponse] = {
    Logger.trace("Fetching tweets")
    ws.url("https://api.twitter.com/1.1/statuses/user_timeline.json?count=15&screen_name=Codestar_nl")
      .withHttpHeaders("Authorization" -> s"Bearer $bearerToken")
      .get()
  }

  private def isInvalidExpiredToken(response: WSResponse): Boolean =
    response.status == 401 &&
      (response.json \ "errors" \\ "code")
        .exists(_.validate[Int].map(_ == 89).getOrElse(false))

  private def fetchNewToken: Future[String] = {
    Logger.trace(s"Retrieving new bearer token")
    ws.url("https://api.twitter.com/oauth2/token")
      .withAuth(
        config.get[String]("twitter.consumer.key"),
        config.get[String]("twitter.consumer.secret"),
        BASIC)
      .post(Map("grant_type" -> Seq("client_credentials")))
      .withFilter(response => (response.json \ "token_type").asOpt[String].contains("bearer"))
      .map(response => (response.json \ "access_token").as[String])
  }
}
