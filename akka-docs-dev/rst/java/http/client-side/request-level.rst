.. _request-level-api-java:

Request-Level Client-Side API
=============================

The request-level API is the most convenient way of using Akka HTTP's client-side functionality. It internally builds upon the
:ref:`host-level-api-java` to provide you with a simple and easy-to-use way of retrieving HTTP responses from remote servers.
Depending on your preference you can pick the flow-based or the future-based variant.

.. note::
  The request-level API is implemented on top of a connection pool that is shared inside the ActorSystem. A consequence of
  using a pool is that long-running requests block a connection while running and starve other requests. Make sure not to use
  the request-level API for long-running requests like long-polling GET requests. Use the :ref:`connection-level-api-java` instead.

Flow-Based Variant
------------------

The flow-based variant of the request-level client-side API is presented by the ``Http().superPool(...)`` method.
It creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
depending on their respective effective URIs.

The ``Flow`` returned by ``Http().superPool(...)`` is very similar to the one from the :ref:`host-level-api-java`, so the
:ref:`using-a-host-connection-pool-java` section also applies here.

However, there is one notable difference between a "host connection pool client flow" for the host-level API and a
"super-pool flow":
Since in the former case the flow has an implicit target host context the requests it takes don't need to have absolute
URIs or a valid ``Host`` header. The host connection pool will automatically add a ``Host`` header if required.

For a super-pool flow this is not the case. All requests to a super-pool must either have an absolute URI or a valid
``Host`` header, because otherwise it'd be impossible to find out which target endpoint to direct the request to.


Future-Based Variant
--------------------

Sometimes your HTTP client needs are very basic. You simply need the HTTP response for a certain request and don't
want to bother with setting up a full-blown streaming infrastructure.

For these cases Akka HTTP offers the ``Http().singleRequest(...)`` method, which simply turns an ``HttpRequest`` instance
into ``Future<HttpResponse>``. Internally the request is dispatched across the (cached) host connection pool for the
request's effective URI.

Just like in the case of the super-pool flow described above the request must have either an absolute URI or a valid
``Host`` header, otherwise the returned future will be completed with an error.

.. includecode:: ../../code/docs/http/javadsl/HttpClientExampleDocTest.java#single-request-example

Using the Future-Based API in Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
When using the ``Future`` based API from inside an ``Actor``, all the usual caveats apply to how one should deal
with the futures completion. For example you should not access the Actors state from within the Future's callbacks
(such as ``map``, ``onComplete``, ...) and instead you should use the ``pipe`` pattern to pipe the result back
to the Actor as a message:

.. includecode:: ../../code/docs/http/javadsl/HttpClientExampleDocTest.java#single-request-in-actor-example
