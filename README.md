C5
====================
[![Build status](https://travis-ci.org/OhmData/c5-replicator.svg)](https://travis-ci.org/OhmData/c5-replicator) [![HuBoard badge](http://img.shields.io/badge/Hu-Board-7965cc.svg)](https://huboard.com/OhmData/c5-replicator)

Building and running
--------------------
To build this project, simply

    mvn install

Replication/consensus API
---------------
C5 makes use of an implementation of the Raft consensus protocol to replicate its write-ahead log; this implementation may
also be used independently of C5 to replicate arbitrary data. For the API, see the project module __c5-api__. Also see modules
__c5db-replicator__ and __c5db-olog__ for the Raft implementation itself. Example usage may be found in the tests in project
module __c5-general-replication__.

Troubleshooting
---------------
On Mac OSX:

    export JAVA_HOME=`/usr/libexec/java_home -v 1.8`

More documentation
------------------
For more information about C5's code and package structure, please see the package-info.java files in project module __c5db__,
located in each package within that module. In addition, see the package-info.java file in the __c5db-replicator__ module in package c5db.replication;
and in the __c5db-olog__ module in package c5db.log, respectively.

C5 is hosted on GitHub at https://github.com/OhmData/c5.
