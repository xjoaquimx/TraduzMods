package com.modtranslator.core;
final class Compat { private Compat() {} static int clamp(int value,int min,int max) { return Math.max(min,Math.min(max,value)); } }
