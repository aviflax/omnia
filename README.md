# Omnia

FIXME: description

## TODO

* Feature: ability to connect a service account via the web UI
* Modify Dropbox and Google Drive Import/Sync to only import docs that should be shared within the org
* Domain model: rename “file” to “document”
* Try having the indexer return Document record instances
* Change `db` to accept/return record instances
* Upgrade Dropbox SDK to 2.0 when it comes out
* Ensure that only Business/Domain/Team service accounts can be added (maybe?)
* Use a consistent approach to loading “dynamic” vars i.e. vars in “service” namespaces
    * in `services.core` we use a multimethod to find and invoke syncing funcs
    * in `web` we use `symbol`, `require`, and `ns-resolve` to find and use auth maps

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
