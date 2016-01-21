# Users and User Sessions

For now, Omnia will not maintain its own canonical user store.
Rather, it will delegate authentication to its configured services.
In other words, if an instance of Omnia is configured to work with
Dropbox, and an initial Dropbox account has been connected in order
to set the Team ID, then any Dropbox user that’s a member of that
team is able to log in to the app and use it.

## The Team/Domain/etc

So how do we determine the “team” or “domain” that is the “official”
such thing for an instance of Omnia? We detect it the first time an
account for a given service is connected. I.E. the first time a Dropbox
account is connected, we determine the ID of their “team” and that
becomes the official team ID for Dropbox (the service) for that instance
of Omnia. Any user that attempts to log in going forward will need to
be a member of that team, and if they are not then they will not be able
to log in.

## Logging In

When a user attempts to access any page at Omnia, we check for the existence
of their session (stored in a cookie). If it doesn’t exist or is expired,
we redirect them to a login page. They can choose a service to use to log in.
There’s no difference between logging in and “registering”, essentially there
is no “registering” at all; or if you like you can think of it as something we
do on the fly during a user’s first login. Certainly from a UX perspective there’s
no “registration” or signing up. You just log in and you’re done.

## Logging Out

The user just clicks a logout link/button and we delete their session. That’s it.

## Connecting an Account vs Logging In

Since both involve authenticating via OAuth, you might be wondering what’s the
difference? Well, the concrete steps involved are almost identical, yes — the
difference is mostly contextual and conceptual. When a user logs in with an account
that hasn’t been already connected, then that account is connected on the fly
as part of the login process. When a user logs in with an account that has already
been connected, they’re just logged in and that’s it. Connecting an account is something
that only a user that’s already logged in can do — basically it’s just about the same
as logging in, but they can’t log in again because they’re already logged in and that
just wouldn’t make any sense.
