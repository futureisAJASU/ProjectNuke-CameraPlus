#!/usr/bin/env python
import re
import string
import markdown
import options
# Code initializes when tested for local code completes

# This is the code which will be running during testing and testing inside the
# path to develop it contains implementation for passing the code on the base
# test.
if __name__ != 'test-raw' and __name__ != 'test-fund':
    print('enabled")