#!/bin/bash
set -e

SDK=/opt/android-sdk
BUILD_TOOLS=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
AAPT2=$BUILD_TOOLS/aapt2
D8=$BUILD_TOOLS/d8
APKSIGNER=$BUILD_TOOLS/apksigner
KOTLINC=/opt/kotlinc/bin/kotlinc

PROJECT=/data/workspace/ScreenRecorder
SRC=$PROJECT/app/src/main
RES=$SRC/res
JAVA_SRC=$SRC/java
LIBS=$PROJECT/lib_jars

OUT=$PROJECT/build_output
BUILD=$PROJECT/build_manual
CLASSES=$BUILD/classes
COMPILED_RES=$BUILD/compiled_res

# Clean
rm -rf $BUILD $OUT
mkdir -p $BUILD $OUT $CLASSES $COMPILED_RES

echo "=== Step 1: Compile resources ==="
find $RES -name "*.xml" | while read f; do
    $AAPT2 compile -o $COMPILED_RES "$f" 2>&1 || true
done
echo "$(ls $COMPILED_RES/ | wc -l) resources compiled"

echo ""
echo "=== Step 2: Link resources ==="
$AAPT2 link \
    -o $BUILD/app.unsigned.apk \
    -I $PLATFORM \
    --manifest $SRC/AndroidManifest.xml \
    --java $BUILD/gen \
    --auto-add-overlay \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    $COMPILED_RES/*.flat 2>&1

R_FILE=$(find $BUILD/gen -name "R.java" | head -1)
echo "R.java: $R_FILE"

echo ""
echo "=== Step 3: Compile Kotlin ==="
# Build classpath from platform + libs
CP="$PLATFORM"
for jar in $LIBS/*.jar; do
    CP="$CP:$jar"
done

find $JAVA_SRC -name "*.kt" > $BUILD/sources.txt
echo "$(wc -l < $BUILD/sources.txt) Kotlin files"

$KOTLINC \
    -classpath "$CP" \
    -jvm-target 1.8 \
    -d $CLASSES \
    -nowarn \
    $R_FILE \
    $(cat $BUILD/sources.txt) 2>&1

echo "Classes compiled"
find $CLASSES -name "*.class" | wc -l

echo ""
echo "=== Step 4: DEX ==="
mkdir -p $BUILD/dex

# Collect all class files
find $CLASSES -name "*.class" > $BUILD/classes.txt
find $LIBS -name "*.jar" | while read jar; do
    # Extract each lib jar and add its classes
    tmpdir=$BUILD/lib_extract_$(basename $jar .jar)
    mkdir -p $tmpdir
    unzip -q -o "$jar" -d $tmpdir 2>/dev/null
    find $tmpdir -name "*.class" >> $BUILD/classes.txt
done

echo "$(wc -l < $BUILD/classes.txt) total class files"

$D8 \
    --output $BUILD/dex \
    --lib $PLATFORM \
    --min-api 26 \
    $(cat $BUILD/classes.txt) 2>&1

echo "DEX:"
ls -lh $BUILD/dex/

echo ""
echo "=== Step 5: Package APK ==="
cd $BUILD
mkdir -p apk_content
cd apk_content
unzip -o ../app.unsigned.apk > /dev/null 2>&1
cp ../dex/classes.dex .
rm -f ../app.unsigned.apk
zip -r -q ../app.unsigned.apk .
cd $BUILD

echo ""
echo "=== Step 6: Align & Sign ==="
$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk 2>&1 || cp app.unsigned.apk app.aligned.apk

keytool -genkeypair \
    -keystore $BUILD/debug.keystore \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -1

$APKSIGNER sign \
    --ks $BUILD/debug.keystore \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out $OUT/ScreenRecorder.apk \
    app.aligned.apk 2>&1

echo ""
echo "=== BUILD COMPLETE ==="
ls -lh $OUT/ScreenRecorder.apk
