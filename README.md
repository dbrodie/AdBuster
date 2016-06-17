# AdBuster

Ad Buster is an Open Source (GPLv3 License) lightweight ad blocker for non-rooted Android devices. It uses a *fake* VPN
to intercept and block the relevant DNS requests. (DNS requests that are not bloked are passed through to your original
DNS server.

_NOTE: The VPN used by this ad blocker is a loopback vpn service. *No data is sent to any third party!*_

## Status

This software is currently still in alpha status. The base features work and should be stable, but some additional features
are missing before a beta will be released:

* Update flow for the app when a new version is released
* CI/Testing
* Nicer UI

## Download

This software is still in alpha stage, use at your own risk.

Releases can be downloaded [here.](https://github.com/dbrodie/AdBuster/releases)

## Development

This proect is developed in Kotlin, please make sure you are using the latest version of the Kotlin plugin for Android
Studio. The VPN is developed based on the pcap4j and dnsjava Java libraries.

Pull requests welcome!

## License

Licensed under GPLv3. Please see the License file for additional information.

