# Omnia

FIXME: description


## TODO

### User-visible

* Ensure that only Business/Domain/Team service accounts can be added (maybe?)
* If there are no accounts/services connected, the home page should say so
* If a user disconnects their last account they should be logged out
* Google Drive: Find a way to detect when a file has been un-shared and un-index it
* When a user disconnects an account, make sure not to un-index docs that other users should be
  able to find
* Gracefully handle the case when a user opts to connect a new account, navigates to the service’s
  connect screen, then chooses “cancel” or “no”
* If a user connects an account that had already been connected to a different account, don’t just
  overwrite the user association of that account. Figure out something better... maybe merge the
  user accounts, TBD.

### Non-user-visible

* Domain model: rename “file” to “document”
* Try having the indexer return Document record instances
* Change `db` to accept/return record instances


## Installation

Download from http://example.com/FIXME.

## Usage

FIXME: explanation

    $ java -jar omnia-0.1.0-standalone.jar [args]

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2015 Avi Flax

Distributed under the {TBD} License.
