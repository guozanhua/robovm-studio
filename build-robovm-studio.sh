#/bin/sh
./getPlugins.sh
set -e
: ${IDEA_HOME?"Need to set IDEA_HOME to point to a valid IntelliJ IDEA installation"}
cd robovm/robovm-idea
awk '!/idea-version/' src/main/resources/META-INF/plugin.xml > plugin.xml.tmp && mv plugin.xml.tmp src/main/resources/META-INF/plugin.xml
mvn -Didea.home="$IDEA_HOME" clean package -Pdeployment
git checkout -- src/main/resources/META-INF/plugin.xml
cd ../..

java -cp . Versioning robovm/robovm-idea/pom.xml robovm/robovm-studio-branding/
java -cp . Versioning robovm/robovm-idea/pom.xml robovm/robovm-studio-branding/src/idea/IdeaApplicationInfo.xml
rm Versioning.class

ant -f build-robovm.xml
rm -rf out/robovm-studio
mkdir -p out/robovm-studio
version=$(<build-robovm/version.txt)
cp out/artifacts/*.mac.zip out/robovm-studio/robovm-studio-$version.zip
cd out/robovm-studio
unzip robovm-studio-$version.zip
cd ../..
appdmg robovm/robovm-studio-dmg/dmg.json out/robovm-studio/robovm-studio-$version.dmg
