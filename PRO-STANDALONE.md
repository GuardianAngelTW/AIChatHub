# AIChatHub Pro standalone build

applicationId: com.aichathub.pro (was com.aichathub)

Reason: master uses Lite and Pro on separate devices; with identical
applicationId the later install overwrites the earlier one. With
com.aichathub.pro, Lite (com.aichathub.lite) and Pro can coexist on the
same device.

CI: builds on push to branch `pro`, releases tagged `pro-v1.2.N`;
retention rule only retires `pro-*` releases, never `lite-*`.

Source: master's AIChatHubPro_v1.2.zip, source unmodified except the
applicationId above. MIT license unchanged.
