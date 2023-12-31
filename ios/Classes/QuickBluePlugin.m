#import "QuickBluePlugin.h"
#if __has_include(<quick_blue_simplify/quick_blue_simplify-Swift.h>)
#import <quick_blue_simplify/quick_blue_simplify-Swift.h>
#else
// Support project import fallback if the generated compatibility header
// is not copied when this plugin is created as a library.
// https://forums.swift.org/t/swift-static-libraries-dont-copy-generated-objective-c-header/19816
#import "quick_blue_simplify-Swift.h"
#endif

@implementation QuickBluePlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftQuickBluePlugin registerWithRegistrar:registrar];
}
@end