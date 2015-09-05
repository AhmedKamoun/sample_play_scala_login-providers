package util

import play.api.Application
import play.api.Play
import play.api.http.{MimeTypes, HeaderNames}
import play.api.libs.ws.WS
import play.api.mvc.{Request, Results, Action, Controller}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class OAuth2Google(application: Application) {
  lazy val googleAuthId = application.configuration.getString("google.client.id").get
  lazy val googleAuthSecret = application.configuration.getString("google.client.secret").get

  def getAuthorizationUrl(redirectUri: String, scope: String, state: String): String = {
    val baseUrl = application.configuration.getString("google.redirect.url").get
    baseUrl.format(googleAuthId, redirectUri, scope, state)
  }

  def getToken(code: String)(implicit request:Request[_]): Future[String] = {
    val tokenResponse = WS.url("https://www.googleapis.com/oauth2/v3/token")(application).
      withQueryString("client_id" -> googleAuthId,
        "client_secret" -> googleAuthSecret,
        "code" -> code,
        "grant_type"->"authorization_code",
        "redirect_uri"-> util.routes.OAuth2Google.callback(None,None).absoluteURL()).
      withHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).
      post(Results.EmptyContent())

    tokenResponse.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold(Future.failed[String](new IllegalStateException("Sod off!"))) { accessToken =>
        Future.successful(accessToken)
      }
    }
  }
}

object OAuth2Google extends Controller {
  lazy val oauth2 = new OAuth2Google(Play.current)

  def callback(codeOpt: Option[String] = None, stateOpt: Option[String] = None) = Action.async { implicit request =>
    (for {
      code <- codeOpt
      state <- stateOpt
      oauthState <- request.session.get("oauth-state")
    } yield {
      if (state == oauthState) {
        oauth2.getToken(code).map { accessToken =>
          Redirect(util.routes.OAuth2Google.success()).withSession("oauth-token" -> accessToken)
        }.recover {
          case ex: IllegalStateException => Unauthorized(ex.getMessage)
        }
      }
      else {
        Future.successful(BadRequest("Invalid google login"))
      }
    }).getOrElse(Future.successful(BadRequest("No parameters supplied")))
  }

  def success() = Action.async { request =>
    implicit val app = Play.current
    request.session.get("oauth-token").fold(Future.successful(Unauthorized("oauth-token not found"))) { authToken =>
      WS.url("https://www.googleapis.com/oauth2/v1/userinfo").
        withQueryString("access_token"-> authToken).
        get().map { response =>
        Ok(response.json)
      }
    }
  }
}