Running Finagle on Scala 2.9.1
==============================

- Use 'scala-2.9.1' branch
- I am using a bit of hack to publish the artifacts to an internal repository. e.g. I am marking the version as "2.9.1_1.9.6" where 1.9.6 is the release version of Finagle.
- I also had to build ostrich and util in a similar fashion.
- There were Java compatibility issues while adding finagle-memcached. Since I am mostly interested in the Scala interface and finagle-http, I am ignoring the Java sources for memcached. Of course, this breaks Java compatibility.
- Current status is "Works for me".
