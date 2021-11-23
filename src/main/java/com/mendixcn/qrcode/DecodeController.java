package com.mendixcn.qrcode;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DecodeController {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  // No real reason to let people upload more than ~64MB
  private static final long MAX_IMAGE_SIZE = 1L << 26;
  // No real reason to deal with more than ~32 megapixels
  private static final int MAX_PIXELS = 1 << 25;
  private static final Map<DecodeHintType, Object> HINTS;
  private static final Map<DecodeHintType, Object> HINTS_PURE;

  static {
    HINTS = new EnumMap<>(DecodeHintType.class);
    HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    HINTS.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.allOf(BarcodeFormat.class));
    HINTS_PURE = new EnumMap<>(HINTS);
    HINTS_PURE.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
  }

  private static final Logger log = Logger.getLogger(DecodeController.class.getName());

  @PostMapping("/decode")
  void decode(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    Collection<Part> parts;
    try {
      parts = request.getParts();
    } catch (Exception e) {
      // Includes IOException, InvalidContentTypeException, other parsing
      // IllegalStateException
      log.info(e.toString());
      errorResponse(request, response, "badimage");
      return;
    }
    Part fileUploadPart = null;
    for (Part part : parts) {
      if (part.getHeader(HttpHeaders.CONTENT_DISPOSITION) != null) {
        fileUploadPart = part;
        break;
      }
    }
    if (fileUploadPart == null) {
      log.info("File upload was not multipart");
      errorResponse(request, response, "badimage");
    } else {
      log.info("Decoding uploaded file " + fileUploadPart.getSubmittedFileName());
      try (InputStream is = fileUploadPart.getInputStream()) {
        processStream(is, request, response);
      }
    }
  }

  private static void processStream(InputStream is, HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    BufferedImage image;
    try {
      image = ImageIO.read(is);
    } catch (Exception e) {
      // Many possible failures from JAI, so just catch anything as a failure
      log.info(e.toString());
      errorResponse(request, response, "badimage");
      return;
    }
    if (image == null) {
      errorResponse(request, response, "badimage");
      return;
    }
    try {
      int height = image.getHeight();
      int width = image.getWidth();
      if (height <= 1 || width <= 1) {
        log.info("Dimensions too small: " + width + 'x' + height);
        errorResponse(request, response, "badimage");
        return;
      } else if (height * width > MAX_PIXELS) {
        log.info("Dimensions too large: " + width + 'x' + height);
        errorResponse(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "badimage");
        return;
      }

      processImage(image, request, response);
    } finally {
      image.flush();
    }
  }

  private static void processImage(BufferedImage image, HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {

    // File outputfile = new File("image.jpg");
    // ImageIO.write(image, "jpg", outputfile);

    LuminanceSource source = new BufferedImageLuminanceSource(image);
    BinaryBitmap bitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
    Collection<Result> results = new ArrayList<>(1);

    try {

      Reader reader = new MultiFormatReader();
      ReaderException savedException = null;
      try {
        // Look for multiple barcodes
        MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(reader);
        Result[] theResults = multiReader.decodeMultiple(bitmap, HINTS);
        if (theResults != null) {
          results.addAll(Arrays.asList(theResults));
        }
      } catch (ReaderException re) {
        savedException = re;
      }

      if (results.isEmpty()) {
        try {
          // Look for pure barcode
          Result theResult = reader.decode(bitmap, HINTS_PURE);
          if (theResult != null) {
            results.add(theResult);
          }
        } catch (ReaderException re) {
          savedException = re;
        }
      }

      if (results.isEmpty()) {
        try {
          // Look for normal barcode in photo
          Result theResult = reader.decode(bitmap, HINTS);
          if (theResult != null) {
            results.add(theResult);
          }
        } catch (ReaderException re) {
          savedException = re;
        }
      }

      if (results.isEmpty()) {
        try {
          // Try again with other binarizer
          BinaryBitmap hybridBitmap = new BinaryBitmap(new HybridBinarizer(source));
          Result theResult = reader.decode(hybridBitmap, HINTS);
          if (theResult != null) {
            results.add(theResult);
          }
        } catch (ReaderException re) {
          savedException = re;
        }
      }

      if (results.isEmpty()) {
        try {
          throw savedException == null ? NotFoundException.getNotFoundInstance() : savedException;
        } catch (FormatException | ChecksumException e) {
          errorResponse(request, response, "format");
        } catch (ReaderException e) { // Including NotFoundException
          errorResponse(request, response, "notfound");
        }
        return;
      }

    } catch (RuntimeException re) {
      // Call out unexpected errors in the log clearly
      log.log(Level.WARNING, "Unexpected exception from library", re);
      throw new ServletException(re);
    }

    response.setContentType(MediaType.PLAIN_TEXT_UTF_8.toString());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
      for (Result result : results) {
        out.write(result.getText());
        out.write('\n');
      }
    }
  }

  private static void errorResponse(HttpServletRequest request, HttpServletResponse response, String key)
      throws ServletException, IOException {
    errorResponse(request, response, HttpServletResponse.SC_BAD_REQUEST, key);
  }

  private static void errorResponse(HttpServletRequest request, HttpServletResponse response, int httpStatus,
      String key) throws ServletException, IOException {
    response.setStatus(httpStatus);
  }
}
