Example Fulcro RAD App: Org Billing Troubleshooting
===================================================

Developers: see the [Dev](#dev) section below.

This is based on a production application that we had for troubleshooting issues with our customers' organizations, their invoices and the charges of individual employees.

The app is based on [Fulcro RAD](https://github.com/fulcrologic/fulcro-rad) and was
created off [fulcro-rad-demo](https://github.com/fulcrologic/fulcro-rad-demo/). It features RAD reports, nested dynamic routers, and custom components (that contain reports) and navigation between the elements.

Requires Java 9+

About the domain
----------------

Organizations have invoices (one per a period, which can be a month or a quarter) and each invoice contains charges for the org's employees based on the services they have used in the period.

Sometimes the period an invoice covers is a multiple of the expected period, which is typically caused by just a few "parts" of the invoice. We display those to help troubleshooting the issue.

Building
--------

### Build

Then run `build.sh` (BEWARE: Stop `shadow-cljs watch` first, if running.)

Tip: You can run the built app locally, for example like this:

     env PORT=3333 APP_ENV=prod java -cp 'app.jar:lib/*' clojure.main -m billing-app.main

or via Docker:

     docker run -it -e PORT=8080 -e APP_ENV=prod -p 8080:8080 billing-app

Dev
---

### Key facts

Starting the app:

**TIP**: _Build the app_ first when doing this for the first time, see above.

* Frontend: `clojure -M:dev:shadow-cli watch main`
* Backend:  `APP_ENV=dev DB_PSW=sa clojure -A:dev:sql` (or, better, via Cursive) then `(require 'development)` and `(development/go)` (`src/sql/development.clj`) to start the server from a Clojure REPL and `(development/restart)` after a code change (if just evaluating the function isn't enough) - see the `(comment ...)` in the file. See *Running locally* below for details.

For config, see `src/shared/config/defaults.edn` and `dev|prod|stage.edn`.

NOTE: During development, all data fetched is cached to .edn files in `.dev-data-cache/` so once cached, you don't need internet/VPN. Use `billing-app.model.cache/evict-all-for` to delete data for a fn from the cache, see its docstring for
details and for deleting all.

The app runs at http://localhost:3030/ 

### IntelliJ setup

Run the action (Help - Find Action...) "Clojure Deps" to open the tool window of the same name. 
There, under _Aliases_, make sure that all of `dev, sql, test` are selected.

When adding a *REPL config for the server*: 

1. Under _Run with Deps_ - _Aliases_, insert `dev,sql` (`test` possibly but not required).
2. Set these environment variables (`;`-separated or using the multi-line editor): 
   * `APP_ENV=dev`

### Running locally

See [IntelliJ setup](#IntelliJ-setup) above for how to prepare the REPL.

See https://github.com/fulcrologic/fulcro-rad-demo/ for more info about running.

Production
----------

In production the app runs behind a proxy that ensures that the user is authenticated and provides it with an OIDC token
that the app checks for the user's roles.

It runs as an uberjar inside a Docker image.
