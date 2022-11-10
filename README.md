# WalkieTalkie

This program transmits sound recorded from microphone
to some other devices running the same program
on the same network segment, so works like Walkie Talkie radio.

<a href="https://f-droid.org/repository/browse/?fdid=org.jsl.wfwt" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80"/></a>

Devices find each other by Android NSD (aka Bonjour),
no any configuration required. Unfortunately Android NSD
implementation is not stable enough, so sometimes application
can not establish connection properly. Application restart
or device reboot usually helps. Audio data is being transmitted
by the unicast channel (TCP/IP), so each device works as a server and
as a client at the same time.

Program was implemented as a demonstration of JS-Collider:
Java high performance scalable NIO framework, see
https://github.com/js-labs/js-collider.
