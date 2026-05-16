#include <Arduino.h>           // OBRIGATÓRIO no PlatformIO!
#include <Adafruit_NeoPixel.h> // A biblioteca que definimos no platformio.ini

// Configurações do LED
#define PIN_RGB 48
#define NUMPIXELS 1
Adafruit_NeoPixel pixels(NUMPIXELS, PIN_RGB, NEO_GRB + NEO_KHZ800);

void setup() {
  Serial.begin(115200);
  
  pixels.begin();
  pixels.setBrightness(20);
  pixels.clear();
  pixels.show();

  Serial.println("Setup do PlatformIO concluído!");
}

void loop() {
  Serial.println("Piscou VERDE a partir do VS Code!");
  
  pixels.setPixelColor(0, pixels.Color(0, 255, 0));
  pixels.show();
  delay(1000);
  
  pixels.clear();
  pixels.show();
  delay(1000);
}