#!/bin/sh

# Install Java 8 JDK and Maven
sudo apt-get install make
sudo add-apt-repository -y ppa:openjdk-r/ppa
sudo apt-get update
sudo apt-get -y install openjdk-8-jdk
sudo apt-get -y install maven