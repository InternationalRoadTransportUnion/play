package controllers;

import java.lang.reflect.InvocationTargetException;
import java.util.Date;

import play.Play;
import play.data.validation.Required;
import play.data.validation.Validation;
import play.libs.Crypto;
import play.libs.Time;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.results.Result;
import play.utils.Java;

public class Secure extends Controller {

    @Before(unless={"login", "authenticate", "logout"})
    static void checkAccess() throws Exception {
        // Authent
        if(!(Boolean)Security.invoke("isConnected")) {
        	if (! request.isAjax())	{
	        	if (flash.get("url") == null) {
	        		flash.put("url", getRedirectUrl()); // seems a good default
	        		flash.keep();
	        	}
	        	boolean allowed = doTrust();
	        	if (! allowed)
        			login();
        	} else {
        		flash.keep();
        		boolean allowed = doTrust();
        		if (! allowed) {
        			throw new Result() {
        			    public void apply(Request request, Response response) {
        			        response.status = Http.StatusCode.UNAUTHORIZED;
        			    }
        			};
        		}
        	}
        }
        // Checks
        Check check = getActionAnnotation(Check.class);
        if(check != null) {
            check(check);
        }
        check = getControllerInheritedAnnotation(Check.class);
        if(check != null) {
            check(check);
        }
    }

	private static boolean doTrust() throws Exception {
		boolean allowed = false;
		if ((Boolean) Trust.invoke("trustPhaseDone")) {
			String username = (String) Trust.invoke("trustedUser");
			if (username != null) {
				allowed = (Boolean) Security.invoke("trustAuthentication", username);
			}
		}
		return allowed;
	}

	private static String getRedirectUrl() {
		return "GET".equals(request.method) ? request.url : Play.ctxPath + "/"; 
	}

    private static void check(Check check) throws Exception {
        for(String profile : check.value()) {
            boolean hasProfile = (Boolean)Security.invoke("check", profile);
            if(!hasProfile) {
                Security.invoke("onCheckFailed", profile);
            }
        }
    }

    // ~~~ Login

    public static void login() throws Exception {
        Http.Cookie remember = request.cookies.get("rememberme");
        if(remember != null) {
            int firstIndex = remember.value.indexOf("-");
            int lastIndex = remember.value.lastIndexOf("-");
            if (lastIndex > firstIndex) {
                String sign = remember.value.substring(0, firstIndex);
                String restOfCookie = remember.value.substring(firstIndex + 1);
                String username = remember.value.substring(firstIndex + 1, lastIndex);
                String time = remember.value.substring(lastIndex + 1);
                Date expirationDate = new Date(Long.parseLong(time)); // surround with try/catch?
                Date now = new Date();
                if (expirationDate == null || expirationDate.before(now)) {
                    logout();
                }
                if(Crypto.sign(restOfCookie).equals(sign)) {
                    session.put("username", username);
                    redirectToOriginalURL();
                }
            }
        }
        
        if(!(Boolean)Security.invoke("isConnected")) {
        	if (doTrust())
        		redirectToOriginalURL();
        } else {
        	redirectToOriginalURL();
        }
        
        flash.keep("url");
        render();
    }

    public static void authenticate(@Required String username, String password, boolean remember) throws Exception {
        // Check tokens
        Boolean allowed = false;
        
        // This is the official method name
        allowed = (Boolean)Security.invoke("authenticate", username, password);
        
        if(Validation.hasErrors() || !allowed) {
            flash.keep("url");
            flash.error("secure.error");
            params.flash();
            login();
        }
	        
        // Remember if needed
        if(remember) {
            Date expiration = new Date();
            String duration = "30d";  // maybe make this override-able 
            expiration.setTime(expiration.getTime() + Time.parseDuration(duration));
            response.setCookie("rememberme", Crypto.sign(username + "-" + expiration.getTime()) + "-" + username + "-" + expiration.getTime(), duration);

        }
        // Redirect to the original URL (or /)
        flash.keep();
        redirectToOriginalURL();
    }

    public static void logout() throws Exception {
        Security.invoke("onDisconnect");
        session.clear();
        response.removeCookie("rememberme");
        Security.invoke("onDisconnected");
        Trust.invoke("onDisconnected");
        flash.success("secure.logout");
        login();
    }

    // ~~~ Utils

    static void redirectToOriginalURL() throws Exception {
        Security.invoke("onAuthenticated");
        String url = flash.get("url");
        if(url == null) {
            url = Play.ctxPath + "/";
        }
        redirect(url);
    }
    
    /**
     * Indicate if a user is currently connected
     * @return  true if the user is connected
     */
    static boolean isConnected() throws Exception {
        return (Boolean) Security.invoke("isConnected");
    }
    
    /**
     * This method checks that a profile is allowed to view this page/method.
     * It complements the @Check annotation usually found on method to allow
     * to programmatically modify the rendered page based on the user's profile.
     * @param profile
     */
    static boolean check(String profile) throws Exception {
    	return (Boolean) Security.invoke("check", profile);
    }

    public static class Trust extends Controller {

    	static boolean trustPhaseDone() {
    		return false;
    	}

    	static String trustedUser() {
    		return null;
    	}

    	static void onDisconnected() {
    	}
    	
    	private static Object invoke(String m, Object... args) throws Exception {
            try {
                return Java.invokeChildOrStatic(Trust.class, m, args);
            } catch(InvocationTargetException e) {
            	Throwable cause = e.getTargetException();
            	if (cause instanceof Error)
            		throw (Error) cause;
                throw (Exception) cause;
            }
        }
    }
    
    public static class Security extends Controller {

        /**
         * This method is called during the authentication process. This is where you check if
         * the user is allowed to log in into the system. This is the actual authentication process
         * against a third party system (most of the time a DB).
         *
         * @param username
         * @param password
         * @return true if the authentication process succeeded
         */
        static boolean authenticate(String username, String password) {
        	session.put("username", username);
            return true;
        }


        /**
         * This method is called during the authentication process if we use Trust.
         *
         * @param username
         * @param password
         * @return true if the authentication process succeeded
         */
        static boolean trustAuthentication(String username) {
            return authenticate(username, null);
        }
        
        /**
         * This method checks that a profile is allowed to view this page/method. This method is called prior
         * to the method's controller annotated with the @Check method. 
         *
         * @param profile
         * @return true if you are allowed to execute this controller method.
         */
        static boolean check(String profile) {
            return true;
        }

        /**
         * This method returns the current connected username
         * @return
         */
        static String connected() {
            return session.get("username");
        }

        /**
         * Indicate if a user is currently connected
         * @return  true if the user is connected
         */
        static boolean isConnected() {
            return session.contains("username");
        }

        /**
         * This method is called after a successful authentication.
         * You need to override this method if you with to perform specific actions (eg. Record the time the user signed in)
         */
        static void onAuthenticated() {
        }

         /**
         * This method is called before a user tries to sign off.
         * You need to override this method if you wish to perform specific actions (eg. Record the name of the user who signed off)
         */
        static void onDisconnect() {
        }

         /**
         * This method is called after a successful sign off.
         * You need to override this method if you wish to perform specific actions (eg. Record the time the user signed off)
         */
        static void onDisconnected() {
        }

        /**
         * This method is called if a check does not succeed. By default it shows the not allowed page (the controller forbidden method).
         * @param profile
         */
        static void onCheckFailed(String profile) {
            forbidden();
        }

        private static Object invoke(String m, Object... args) throws Exception {

            try {
                return Java.invokeChildOrStatic(Security.class, m, args);       
            } catch(InvocationTargetException e) {
            	Throwable cause = e.getTargetException();
            	if (cause instanceof Error)
            		throw (Error) cause;
                throw (Exception) cause;
            }
        }

    }

}
